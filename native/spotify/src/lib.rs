//! Núcleo de Spotify de Moody.
//!
//! No reproduce nada por sí mismo: abre una sesión de librespot con el token
//! OAuth que consigue la app y levanta un servidor HTTP en 127.0.0.1. Cada
//! `GET /track/<id base62>?kbps=320` responde con el archivo Ogg Vorbis de esa
//! pista ya descifrado (con soporte de `Range`), así que el reproductor normal
//! de Moody lo toca como cualquier otra fuente y le aplica el ecualizador, el
//! Audio Lab, el crossfade, etc.
//!
//! La forma de iniciar sesión (token OAuth del client id de escritorio, la
//! credencial reutilizable guardada aparte y el parche de librespot-core) viene
//! de Square: https://github.com/Lelonio/Square (GPL-3.0).

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use librespot_audio::{AudioDecrypt, AudioFile};
use librespot_core::{
    audio_key::AudioKey, authentication::Credentials, cache::Cache, config::SessionConfig,
    session::Session, spotify_uri::SpotifyUri, FileId, SpotifyId,
};
use bytes::Bytes;
use http::{Request, Method, header::{AUTHORIZATION, CONTENT_TYPE, ACCEPT}};
use librespot_metadata::audio::{AudioFileFormat, AudioItem};
use once_cell::sync::OnceCell;
use std::{
    collections::HashMap,
    io::{BufRead, BufReader, Read, Seek, SeekFrom, Write},
    net::{TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::Mutex,
    time::Duration,
};

/// Los archivos Ogg de Spotify llevan una cabecera propia de 0xa7 bytes antes del Ogg real.
const SPOTIFY_OGG_HEADER_END: u64 = 0xa7;
/// Límite de la caché de audio.
const AUDIO_CACHE_LIMIT: u64 = 400 * 1024 * 1024;
const CHUNK: usize = 64 * 1024;

static RUNTIME: OnceCell<tokio::runtime::Runtime> = OnceCell::new();
static SESSION: Mutex<Option<Session>> = Mutex::new(None);
static ROOT: Mutex<Option<PathBuf>> = Mutex::new(None);
static PORT: OnceCell<u16> = OnceCell::new();
/// Pista + calidad -> archivo elegido y su clave. Spotify raciona las claves, así que se guardan.
static OPENED: Mutex<Option<HashMap<String, Resolved>>> = Mutex::new(None);

#[derive(Clone, Copy)]
struct Resolved {
    track: SpotifyId,
    file: FileId,
    format: AudioFileFormat,
    key: Option<AudioKey>,
    bytes_per_second: usize,
}

type Res<T> = Result<T, String>;

fn runtime() -> Res<&'static tokio::runtime::Runtime> {
    RUNTIME.get_or_try_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("moodyspotify")
            .build()
            .map_err(|e| format!("runtime: {e}"))
    })
}

// ---- sesión -----------------------------------------------------------------------------------

/// Conecta (o reconecta) y devuelve el nombre de usuario.
fn start(dir: &str, token: &str) -> Res<String> {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("MoodySpotify").with_max_level(log::LevelFilter::Info));

    let root = PathBuf::from(dir);
    *ROOT.lock().map_err(|_| "root poisoned")? = Some(root.clone());
    let session = connect(&root, token)?;

    // Solo cuentas Premium reciben audio.
    if let Some(kind) = session.get_user_attribute("type") {
        if kind != "premium" {
            session.shutdown();
            return Err(format!("not premium ({kind})"));
        }
    }
    let user = session.username();
    let old = SESSION.lock().map_err(|_| "session poisoned")?.replace(session);
    if let Some(old) = old {
        old.shutdown();
    }
    if let Ok(mut opened) = OPENED.lock() {
        *opened = Some(HashMap::new());
    }
    server_port()?;
    Ok(user)
}

fn connect(root: &Path, token: &str) -> Res<Session> {
    let rt = runtime()?;
    // Session::new toma Handle::current(): fuera del runtime de tokio entra en pánico
    let _runtime = rt.enter();
    let credentials_dir = root.join("credentials");
    let kept_dir = root.join("reusable");
    let audio_dir = root.join("audio");
    let cache = Cache::new(Some(credentials_dir.as_path()), None, Some(audio_dir.as_path()), Some(AUDIO_CACHE_LIMIT))
        .map_err(|e| format!("cache: {e}"))?;
    let kept = Cache::new(Some(kept_dir.as_path()), None, None, None).map_err(|e| format!("cache: {e}"))?;

    // Primero la credencial reutilizable que dio el servidor la última vez; el token solo es la puerta de entrada.
    let mut attempts: Vec<Credentials> = kept.credentials().into_iter().collect();
    if !token.is_empty() {
        attempts.push(Credentials::with_access_token(token));
    }
    if attempts.is_empty() {
        return Err("no credentials".into());
    }

    let last = attempts.len() - 1;
    let mut failure = String::from("no credentials");
    for (n, credentials) in attempts.into_iter().enumerate() {
        cache.save_credentials(&credentials);
        let session = Session::new(SessionConfig::default(), Some(cache.clone()));
        let attempt = rt.block_on(async {
            tokio::time::timeout(Duration::from_secs(25), session.connect(credentials.clone(), true)).await
        });
        match attempt {
            Ok(Ok(())) => {
                if let Some(cache) = session.cache() {
                    if let Some(reusable) = cache.credentials() {
                        kept.save_credentials(&reusable);
                    }
                    // login5 firma con lo que haya en la caché; tiene que ser lo que abrió esta sesión
                    cache.save_credentials(&credentials);
                }
                return Ok(session);
            }
            Ok(Err(e)) => failure = format!("connect: {e}"),
            Err(_) => failure = "connect: timed out".into(),
        }
        session.shutdown();
        if n < last {
            let _ = std::fs::remove_dir_all(&kept_dir);
        }
    }
    Err(failure)
}

/// La sesión viva; si se cayó, se abre otra con la credencial guardada.
fn session() -> Res<Session> {
    if let Some(session) = SESSION.lock().map_err(|_| "session poisoned")?.as_ref() {
        if !session.is_invalid() {
            return Ok(session.clone());
        }
    }
    let root = ROOT.lock().map_err(|_| "root poisoned")?.clone().ok_or("not started")?;
    let session = connect(&root, "")?;
    SESSION.lock().map_err(|_| "session poisoned")?.replace(session.clone());
    Ok(session)
}

fn access_token() -> Res<String> {
    let session = session()?;
    let token = runtime()?
        .block_on(session.login5().auth_token())
        .map_err(|e| format!("token: {e}"))?;
    Ok(token.access_token)
}

/// Pathfinder searchDesktop over the authenticated librespot session.
/// Request shape and operation follow Convx's GPL-3.0 SpotifyCanvas reference.
fn search(query: &str, hash: &str, app_version: &str) -> Res<String> {
    let session = session()?;
    let rt = runtime()?;
    rt.block_on(async {
        let token = session.login5().auth_token().await.map_err(|e| format!("token: {e}"))?;
        let client_token = session.spclient().client_token().await.map_err(|e| format!("client token: {e}"))?;
        let body = format!(r#"{{"variables":{{"searchTerm":{},"offset":0,"limit":10,"numberOfTopResults":5,"includeAudiobooks":false,"includeArtistHasConcertsField":false,"includePreReleases":false,"includeLocalConcertsField":false,"includeAuthors":false}},"operationName":"searchDesktop","extensions":{{"persistedQuery":{{"version":1,"sha256Hash":"{}"}}}}}}"#,
            serde_json::to_string(query).map_err(|e| format!("query: {e}"))?, hash);
        let request = Request::builder().method(Method::POST).uri("https://api-partner.spotify.com/pathfinder/v2/query")
            .header(AUTHORIZATION, format!("Bearer {}", token.access_token))
            .header("client-token", client_token)
            .header("app-platform", "WebPlayer")
            .header("spotify-app-version", app_version)
            .header("origin", "https://open.spotify.com")
            .header("referer", "https://open.spotify.com/")
            .header(ACCEPT, "application/json")
            .header(CONTENT_TYPE, "application/json; charset=UTF-8")
            .body(Bytes::from(body)).map_err(|e| format!("request: {e}"))?;
        let response = session.http_client().request(request).await.map_err(|e| format!("request: {e}"))?;
        let status = response.status();
        let bytes = response.into_body();
        let body = http_body_util::BodyExt::collect(bytes).await.map_err(|e| format!("response: {e}"))?.to_bytes();
        if !status.is_success() { return Err(format!("HTTP {}: {}", status.as_u16(), String::from_utf8_lossy(&body))); }
        String::from_utf8(body.to_vec()).map_err(|e| format!("response utf8: {e}"))
    })
}

fn sign_out() {
    if let Ok(mut s) = SESSION.lock() {
        if let Some(s) = s.take() {
            s.shutdown();
        }
    }
    if let Ok(root) = ROOT.lock() {
        if let Some(root) = root.as_ref() {
            let _ = std::fs::remove_dir_all(root.join("reusable"));
            let _ = std::fs::remove_dir_all(root.join("credentials"));
        }
    }
}

// ---- pistas -----------------------------------------------------------------------------------

fn formats(kbps: u32) -> Vec<AudioFileFormat> {
    use AudioFileFormat::*;
    match kbps {
        k if k <= 96 => vec![OGG_VORBIS_96, OGG_VORBIS_160, OGG_VORBIS_320, MP3_96, MP3_160, MP3_256, MP3_320],
        k if k <= 160 => vec![OGG_VORBIS_160, OGG_VORBIS_96, OGG_VORBIS_320, MP3_160, MP3_96, MP3_256, MP3_320],
        _ => vec![OGG_VORBIS_320, OGG_VORBIS_160, MP3_320, MP3_256, OGG_VORBIS_96, MP3_160, MP3_96],
    }
}

fn bytes_per_second(format: AudioFileFormat) -> usize {
    use AudioFileFormat::*;
    let kib = match format {
        OGG_VORBIS_96 | MP3_96 => 12.0,
        OGG_VORBIS_160 | MP3_160 => 20.0,
        MP3_256 => 32.0,
        _ => 40.0,
    };
    (kib * 1024.0) as usize
}

fn is_ogg(format: AudioFileFormat) -> bool {
    matches!(format, AudioFileFormat::OGG_VORBIS_96 | AudioFileFormat::OGG_VORBIS_160 | AudioFileFormat::OGG_VORBIS_320)
}

async fn playable_item(session: &Session, uri: SpotifyUri) -> Res<AudioItem> {
    let item = AudioItem::get_file(session, uri).await.map_err(|e| format!("metadata: {e}"))?;
    if item.availability.is_ok() && !item.files.is_empty() {
        return Ok(item);
    }
    if let Some(librespot_metadata::track::Tracks(alternatives)) = item.alternatives.clone() {
        for alternative in alternatives {
            if let Ok(other) = AudioItem::get_file(session, alternative).await {
                if other.availability.is_ok() && !other.files.is_empty() {
                    return Ok(other);
                }
            }
        }
    }
    Err("track not available".into())
}

fn resolve(session: &Session, base62: &str, kbps: u32) -> Res<Resolved> {
    let cache_key = format!("{base62}@{kbps}");
    if let Some(found) = OPENED.lock().ok().and_then(|m| m.as_ref().and_then(|m| m.get(&cache_key).copied())) {
        return Ok(found);
    }
    let uri = SpotifyUri::from_uri(&format!("spotify:track:{base62}")).map_err(|e| format!("bad id: {e}"))?;
    let rt = runtime()?;
    let resolved = rt.block_on(async {
        let item = playable_item(session, uri).await?;
        let track: SpotifyId = (&item.track_id).try_into().map_err(|_| "not a track".to_string())?;
        let (format, file) = formats(kbps)
            .into_iter()
            .find_map(|f| item.files.get(&f).map(|file| (f, *file)))
            .ok_or("no playable format")?;
        let key = match tokio::time::timeout(Duration::from_secs(20), session.audio_key().request(track, file)).await {
            Ok(Ok(key)) => Some(key),
            Ok(Err(e)) => return Err(format!("audio key: {e}")),
            Err(_) => return Err("audio key: timed out".into()),
        };
        Ok::<Resolved, String>(Resolved { track, file, format, key, bytes_per_second: bytes_per_second(format) })
    })?;
    if let Ok(mut m) = OPENED.lock() {
        m.get_or_insert_with(HashMap::new).insert(cache_key, resolved);
    }
    Ok(resolved)
}

/// El archivo descifrado, posicionado al principio del audio, y su tamaño útil.
fn open(base62: &str, kbps: u32) -> Res<(Box<dyn ReadSeek>, u64, u64, &'static str)> {
    let session = session()?;
    let resolved = resolve(&session, base62, kbps)?;
    let rt = runtime()?;
    let file = rt
        .block_on(AudioFile::open(&session, resolved.file, resolved.bytes_per_second))
        .map_err(|e| format!("open: {e}"))?;
    let controller = file.get_stream_loader_controller().map_err(|e| format!("controller: {e}"))?;
    let total = controller.len() as u64;
    controller.set_stream_mode();
    let decrypted = AudioDecrypt::new(resolved.key, file);
    let (offset, mime) = if is_ogg(resolved.format) { (SPOTIFY_OGG_HEADER_END, "audio/ogg") } else { (0, "audio/mpeg") };
    if total <= offset {
        return Err("empty file".into());
    }
    Ok((Box::new(decrypted), offset, total - offset, mime))
}

trait ReadSeek: Read + Seek {}
impl<T: Read + Seek> ReadSeek for T {}

// ---- servidor local ---------------------------------------------------------------------------

fn server_port() -> Res<u16> {
    PORT.get_or_try_init(|| {
        let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| format!("bind: {e}"))?;
        let port = listener.local_addr().map_err(|e| format!("addr: {e}"))?.port();
        std::thread::Builder::new()
            .name("moodyspotify-http".into())
            .spawn(move || {
                for stream in listener.incoming().flatten() {
                    let _ = std::thread::Builder::new().name("moodyspotify-conn".into()).spawn(move || {
                        let _ = handle(stream);
                    });
                }
            })
            .map_err(|e| format!("thread: {e}"))?;
        Ok(port)
    })
    .copied()
}

fn handle(stream: TcpStream) -> std::io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line)?;
    let mut range: Option<(u64, Option<u64>)> = None;
    let mut has_range = false;
    let mut invalid_range = false;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            if name.trim().eq_ignore_ascii_case("range") {
                has_range = true;
                range = parse_range(value.trim());
                invalid_range = range.is_none();
            }
        }
    }
    let mut out = stream;
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let target = parts.next().unwrap_or("");
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let Some(base62) = path.strip_prefix("/track/").filter(|id| id.len() == 22 && id.chars().all(|c| c.is_ascii_alphanumeric())) else {
        return reply_error(&mut out, 404, "not found");
    };
    let kbps = query
        .split('&')
        .find_map(|kv| kv.strip_prefix("kbps="))
        .and_then(|v| v.parse().ok())
        .unwrap_or(320u32);

    // AudioFile::open can fetch/decrypt the track. For HEAD, answer from the
    // known librespot cache entry whenever the resolved file and its size exist.
    if method.eq_ignore_ascii_case("HEAD") {
        let cache_key = format!("{base62}@{kbps}");
        let cached = OPENED.lock().ok().and_then(|m| m.as_ref().and_then(|m| m.get(&cache_key).copied()))
            .or_else(|| session().ok().and_then(|session| resolve(&session, base62, kbps).ok()));
        if let (Some(resolved), Ok(session)) = (cached, session()) {
            if let Some(path) = session.cache().and_then(|cache| cache.file_path(resolved.file)) {
                if let Ok(metadata) = std::fs::metadata(path) {
                    let offset = if is_ogg(resolved.format) { SPOTIFY_OGG_HEADER_END } else { 0 };
                    if metadata.len() > offset {
                        let size = metadata.len() - offset;
                        let mime = if is_ogg(resolved.format) { "audio/ogg" } else { "audio/mpeg" };
                        if invalid_range {
                            let head = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                            return out.write_all(head.as_bytes());
                        }
                        let (start, end) = match range {
                            Some((s, Some(u64::MAX))) if has_range => (size.saturating_sub((u64::MAX - s) + 1), size - 1),
                            Some((s, e)) => (s, e.unwrap_or(size - 1).min(size - 1)),
                            None => (0, size - 1),
                        };
                        if start >= size || start > end || end >= size {
                            let head = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                            return out.write_all(head.as_bytes());
                        }
                        let length = end - start + 1;
                        let status = if has_range { "206 Partial Content" } else { "200 OK" };
                        let content_range = if has_range { format!("Content-Range: bytes {start}-{end}/{size}\r\n") } else { String::new() };
                        let head = format!("HTTP/1.1 {status}\r\nContent-Type: {mime}\r\nContent-Length: {length}\r\nAccept-Ranges: bytes\r\n{content_range}Connection: close\r\n\r\n");
                        return out.write_all(head.as_bytes());
                    }
                }
            }
        }
    }

    let opened = std::panic::catch_unwind(|| open(base62, kbps)).unwrap_or_else(|_| Err("panic".into()));
    let (mut file, offset, size, mime) = match opened {
        Ok(v) => v,
        Err(e) => {
            log::warn!("track {base62}: {e}");
            return reply_error(&mut out, 503, &e);
        }
    };

    if invalid_range {
        let head = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return out.write_all(head.as_bytes());
    }

    let (start, end) = match range {
        Some((s, Some(u64::MAX))) if has_range => (size.saturating_sub((u64::MAX - s) + 1), size - 1),
        Some((s, e)) => (s, e.unwrap_or(size - 1).min(size - 1)),
        None => (0, size - 1),
    };
    if start >= size || start > end || end >= size {
        let head = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return out.write_all(head.as_bytes());
    }
    let length = end - start + 1;
    let status = if has_range { "206 Partial Content" } else { "200 OK" };
    let mut head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {mime}\r\nContent-Length: {length}\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n"
    );
    if has_range {
        head.push_str(&format!("Content-Range: bytes {start}-{end}/{size}\r\n"));
    }
    head.push_str("\r\n");
    out.write_all(head.as_bytes())?;
    if method.eq_ignore_ascii_case("HEAD") {
        return Ok(());
    }

    file.seek(SeekFrom::Start(offset + start))?;
    let mut left = length;
    let mut buf = vec![0u8; CHUNK];
    while left > 0 {
        let want = (left as usize).min(CHUNK);
        let n = file.read(&mut buf[..want])?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        left -= n as u64;
    }
    out.flush()
}

fn parse_range(value: &str) -> Option<(u64, Option<u64>)> {
    let spec = value.strip_prefix("bytes=")?;
    if spec.contains(',') { return None; }
    let (a, b) = spec.split_once('-')?;
    if a.is_empty() { let suffix: u64 = b.parse().ok()?; if suffix == 0 { return None; } return Some((u64::MAX - (suffix - 1), Some(u64::MAX))); }
    let start = a.trim().parse().ok()?;
    let end = if b.trim().is_empty() { None } else { Some(b.trim().parse().ok()?) };
    Some((start, end))
}

#[no_mangle]
pub extern "system" fn Java_com_mochyy_mochymusic_module_spotify_SpotifyNative_nativeSearch<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>, query: JString<'l>, hash: JString<'l>, app_version: JString<'l>) -> jstring {
    let query = text(&mut env, &query); let hash = text(&mut env, &hash); let app_version = text(&mut env, &app_version);
    let out = guarded(move || search(&query, &hash, &app_version)); answer(&mut env, out)
}

fn reply_error(out: &mut TcpStream, code: u16, message: &str) -> std::io::Result<()> {
    let reason = if code == 404 { "Not Found" } else { "Service Unavailable" };
    let body = message.replace(['\r', '\n'], " ");
    let head = format!(
        "HTTP/1.1 {code} {reason}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    out.write_all(head.as_bytes())
}

// ---- JNI --------------------------------------------------------------------------------------

fn text(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(|s| s.into()).unwrap_or_default()
}

fn answer(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn guarded(f: impl FnOnce() -> Res<String> + std::panic::UnwindSafe) -> String {
    match std::panic::catch_unwind(f) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => format!("error:{e}"),
        Err(panic) => {
            let why = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_default();
            format!("error:panic {why}")
        }
    }
}

/// Conecta y devuelve el usuario, o `error:<motivo>`.
#[no_mangle]
pub extern "system" fn Java_com_mochyy_mochymusic_module_spotify_SpotifyNative_nativeStart<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    dir: JString<'l>,
    token: JString<'l>,
) -> jstring {
    let dir = text(&mut env, &dir);
    let token = text(&mut env, &token);
    let out = guarded(move || start(&dir, &token));
    answer(&mut env, out)
}

/// Puerto del servidor local, o -1.
#[no_mangle]
pub extern "system" fn Java_com_mochyy_mochymusic_module_spotify_SpotifyNative_nativePort<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jint {
    PORT.get().map(|p| *p as jint).unwrap_or(-1)
}

/// Un token de acceso para la Web API (búsqueda), o `error:<motivo>`.
#[no_mangle]
pub extern "system" fn Java_com_mochyy_mochymusic_module_spotify_SpotifyNative_nativeAccessToken<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let out = guarded(access_token);
    answer(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mochyy_mochymusic_module_spotify_SpotifyNative_nativeSignOut<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    let _ = std::panic::catch_unwind(sign_out);
}
