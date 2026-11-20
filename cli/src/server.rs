//! HTTPS 接收服务器：`GET /api/v1/info`、`POST /api/v2/todos`。

use std::io;
use std::net::{Ipv4Addr, TcpListener};
use std::path::PathBuf;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::rejection::BytesRejection;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::markdown::{self, MAX_DEPTH, Note, Project};
use crate::{MAX_BODY_BYTES, PROTOCOL_VERSION, constant_time_eq, storage};

/// 保存下来的一个文件。
#[derive(Debug, Clone)]
pub struct SavedFile {
    pub path: PathBuf,
    /// 顶层项目名。
    pub project: String,
    /// 写进这个文件的待办条数。
    pub count: usize,
    pub bytes: usize,
}

/// 一次成功接收的信息，交给回调打印日志。
#[derive(Debug, Clone)]
pub struct Saved {
    pub device: Option<String>,
    pub files: Vec<SavedFile>,
    /// 只给人看的提醒，不写进文件。
    pub notes: Vec<Note>,
}

pub type OnSaved = Arc<dyn Fn(&Saved) + Send + Sync>;

/// 服务器共享状态。
pub struct AppState {
    pub token: String,
    pub name: String,
    pub out_dir: PathBuf,
    pub on_saved: OnSaved,
}

/// 请求体（protocol 已经单独校验过）。
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Upload {
    #[serde(default)]
    projects: Vec<Project>,
    #[serde(default)]
    device: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    exported_at: Option<String>,
}

/// 统一的 JSON 错误响应 `{"error": "..."}`。
#[derive(Debug)]
pub struct ApiError(pub StatusCode, pub String);

impl ApiError {
    fn new(status: StatusCode, msg: impl Into<String>) -> Self {
        Self(status, msg.into())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

/// 检查 `Authorization: Bearer <token>`。
fn authorize(headers: &HeaderMap, token: &str) -> Result<(), ApiError> {
    let provided = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| {
            let (scheme, rest) = v.split_once(' ')?;
            scheme.eq_ignore_ascii_case("bearer").then(|| rest.trim())
        })
        .unwrap_or("");
    if constant_time_eq(provided.as_bytes(), token.as_bytes()) {
        Ok(())
    } else {
        Err(ApiError::new(StatusCode::UNAUTHORIZED, "token 无效，二维码可能已过期，请重新扫码"))
    }
}

async fn info(State(s): State<Arc<AppState>>, headers: HeaderMap) -> Result<Json<Value>, ApiError> {
    authorize(&headers, &s.token)?;
    Ok(Json(json!({
        "app": "tododrop",
        "protocol": PROTOCOL_VERSION,
        "name": s.name,
        "version": env!("CARGO_PKG_VERSION"),
    })))
}

async fn receive(
    State(s): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Bytes, BytesRejection>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    authorize(&headers, &s.token)?;

    let body = body.map_err(|e| {
        if e.status() == StatusCode::PAYLOAD_TOO_LARGE {
            ApiError::new(StatusCode::PAYLOAD_TOO_LARGE, "内容超过 2 MiB 上限")
        } else {
            ApiError::new(StatusCode::BAD_REQUEST, format!("读取请求体失败：{e}"))
        }
    })?;
    // 先只看 protocol：版本不对时给出明确提示，而不是字段解析错误
    let raw: Value = serde_json::from_slice(&body)
        .map_err(|e| ApiError::new(StatusCode::BAD_REQUEST, format!("请求体不是合法的 JSON：{e}")))?;
    let protocol = raw.get("protocol").and_then(Value::as_u64);
    if protocol != Some(u64::from(PROTOCOL_VERSION)) {
        let got = protocol.map(|p| p.to_string()).unwrap_or_else(|| "缺失".into());
        return Err(ApiError::new(
            StatusCode::BAD_REQUEST,
            format!("不支持的协议版本 {got}（CLI 支持 {PROTOCOL_VERSION}），请把 App 和 CLI 升级到同一版本"),
        ));
    }
    let up: Upload = serde_json::from_value(raw)
        .map_err(|e| ApiError::new(StatusCode::BAD_REQUEST, format!("请求体格式不对：{e}")))?;
    if up.projects.iter().any(|p| markdown::depth(p) > MAX_DEPTH) {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("项目层级超过 {MAX_DEPTH} 层")));
    }

    let rendered: Vec<_> = up.projects.iter().filter_map(markdown::render).collect();
    if rendered.is_empty() {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "没有可保存的内容"));
    }
    let notes = markdown::collect_notes(&up.projects);

    let dir = s.out_dir.clone();
    let files: Vec<(String, String)> = rendered.iter().map(|r| (r.project.clone(), r.markdown.clone())).collect();
    let now = chrono::Local::now();
    let paths = tokio::task::spawn_blocking(move || storage::save_all(&dir, &now, &files))
        .await
        .map_err(|e| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, format!("保存任务异常：{e}")))?
        .map_err(|e| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, format!("保存文件失败：{e}")))?;

    let saved_files: Vec<SavedFile> = rendered
        .into_iter()
        .zip(paths)
        .map(|(r, path)| SavedFile { path, project: r.project, count: r.count, bytes: r.markdown.len() })
        .collect();
    let response = json!({
        "files": saved_files.iter().map(|f| json!({
            "project": f.project,
            "savedAs": f.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            "count": f.count,
        })).collect::<Vec<_>>(),
        "notes": notes.len(),
    });
    (s.on_saved)(&Saved { device: up.device, files: saved_files, notes });
    Ok((StatusCode::CREATED, Json(response)))
}

async fn not_found() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "接口不存在")
}

async fn method_not_allowed() -> ApiError {
    ApiError::new(StatusCode::METHOD_NOT_ALLOWED, "不支持该请求方法")
}

/// 构建路由。`/info` 一直留在 v1 路径下，旧版 App 也能探测到版本不一致并给出提示。
pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/api/v1/info", get(info))
        .route("/api/v2/todos", post(receive))
        .fallback(not_found)
        .method_not_allowed_fallback(method_not_allowed)
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .with_state(state)
}

/// 绑定 `0.0.0.0:port`；端口被占用时改用系统分配的随机端口。
pub fn bind_listener(port: u16) -> io::Result<TcpListener> {
    let listener = match TcpListener::bind((Ipv4Addr::UNSPECIFIED, port)) {
        Ok(l) => l,
        Err(e) if e.kind() == io::ErrorKind::AddrInUse && port != 0 => TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0))?,
        Err(e) => return Err(e),
    };
    listener.set_nonblocking(true)?;
    Ok(listener)
}

/// 在已绑定的监听器上启动 HTTPS 服务，直到 `handle` 触发关闭。
pub async fn serve(
    listener: TcpListener,
    tls: Arc<rustls::ServerConfig>,
    state: Arc<AppState>,
    handle: axum_server::Handle<std::net::SocketAddr>,
) -> io::Result<()> {
    let config = axum_server::tls_rustls::RustlsConfig::from_config(tls);
    axum_server::from_tcp_rustls(listener, config)?
        .handle(handle)
        .serve(router(state).into_make_service())
        .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{Body, to_bytes};
    use axum::http::Request;
    use std::sync::Mutex;
    use tower::ServiceExt as _;

    const TOKEN: &str = "test-token-123456789012";

    fn state(dir: &std::path::Path) -> (Arc<AppState>, Arc<Mutex<Vec<Saved>>>) {
        let log = Arc::new(Mutex::new(Vec::new()));
        let log2 = log.clone();
        let st = Arc::new(AppState {
            token: TOKEN.into(),
            name: "TEST-PC".into(),
            out_dir: dir.to_path_buf(),
            on_saved: Arc::new(move |s: &Saved| log2.lock().unwrap().push(s.clone())),
        });
        (st, log)
    }

    async fn call(app: Router, req: Request<Body>) -> (StatusCode, Value) {
        let resp = app.oneshot(req).await.unwrap();
        let status = resp.status();
        let body = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
    }

    fn post(token: &str, body: impl Into<Body>) -> Request<Body> {
        Request::post("/api/v2/todos")
            .header("authorization", format!("Bearer {token}"))
            .header("content-type", "application/json")
            .body(body.into())
            .unwrap()
    }

    #[tokio::test]
    async fn info_requires_token() {
        let tmp = tempfile::tempdir().unwrap();
        let (st, _) = state(tmp.path());
        let app = router(st);

        let (s, v) = call(app.clone(), Request::get("/api/v1/info").body(Body::empty()).unwrap()).await;
        assert_eq!(s, StatusCode::UNAUTHORIZED);
        assert!(v["error"].is_string());

        let req = Request::get("/api/v1/info").header("authorization", "Bearer wrong").body(Body::empty()).unwrap();
        assert_eq!(call(app.clone(), req).await.0, StatusCode::UNAUTHORIZED);

        let req = Request::get("/api/v1/info").header("authorization", format!("bearer {TOKEN}")).body(Body::empty()).unwrap();
        let (s, v) = call(app, req).await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(v["app"], "tododrop");
        assert_eq!(v["protocol"], PROTOCOL_VERSION);
        assert_eq!(v["name"], "TEST-PC");
    }

    #[tokio::test]
    async fn receive_writes_one_file_per_top_level_project() {
        let tmp = tempfile::tempdir().unwrap();
        let (st, log) = state(tmp.path());
        let body = json!({
            "protocol": 2,
            "device": "Xiaomi 14",
            "projects": [
                {
                    "name": "工作",
                    "todos": ["写周报\n第二行"],
                    "notes": ["周五前交"],
                    "children": [{"name": "后端", "todos": ["接口文档"]}]
                },
                {"name": "只有提醒", "notes": ["不会生成文件"]},
                {"name": "生活", "todos": ["买牛奶"]}
            ]
        })
        .to_string();
        let (s, v) = call(router(st), post(TOKEN, body)).await;
        assert_eq!(s, StatusCode::CREATED, "{v}");

        let files = v["files"].as_array().unwrap();
        assert_eq!(files.len(), 2);
        assert_eq!(files[0]["project"], "工作");
        assert_eq!(files[0]["count"], 2);
        assert_eq!(files[1]["project"], "生活");
        assert_eq!(v["notes"], 2);
        let work = files[0]["savedAs"].as_str().unwrap();
        assert!(work.starts_with("TODOS_工作_") && work.ends_with(".md"), "{work}");
        assert_eq!(
            std::fs::read_to_string(tmp.path().join(work)).unwrap(),
            "- [ ] 写周报\n  第二行\n\n## 后端\n\n- [ ] 接口文档\n"
        );
        let life = files[1]["savedAs"].as_str().unwrap();
        assert_eq!(std::fs::read_to_string(tmp.path().join(life)).unwrap(), "- [ ] 买牛奶\n");
        assert_eq!(std::fs::read_dir(tmp.path()).unwrap().count(), 2);

        let log = log.lock().unwrap();
        assert_eq!(log.len(), 1);
        assert_eq!(log[0].device.as_deref(), Some("Xiaomi 14"));
        assert_eq!(log[0].files.len(), 2);
        assert_eq!(log[0].notes.len(), 2);
        assert_eq!(log[0].notes[1].path, vec!["只有提醒".to_string()]);
    }

    #[tokio::test]
    async fn receive_rejects_bad_requests() {
        let tmp = tempfile::tempdir().unwrap();
        let (st, log) = state(tmp.path());
        let app = router(st);

        let ok = json!({"protocol": 2, "projects": [{"name": "P", "todos": ["x"]}]}).to_string();
        assert_eq!(call(app.clone(), post("nope", ok)).await.0, StatusCode::UNAUTHORIZED);
        assert_eq!(call(app.clone(), post(TOKEN, "not json")).await.0, StatusCode::BAD_REQUEST);

        // 旧版 App 的请求体
        let v1 = json!({"protocol": 1, "markdown": "- [ ] x\n"}).to_string();
        let (s, v) = call(app.clone(), post(TOKEN, v1)).await;
        assert_eq!(s, StatusCode::BAD_REQUEST);
        assert!(v["error"].as_str().unwrap().contains("协议版本"), "{v}");

        let empty = json!({"protocol": 2, "projects": [{"name": "P", "todos": ["  \n "], "notes": ["n"]}]}).to_string();
        assert_eq!(call(app.clone(), post(TOKEN, empty)).await.0, StatusCode::BAD_REQUEST);
        let none = json!({"protocol": 2, "projects": []}).to_string();
        assert_eq!(call(app.clone(), post(TOKEN, none)).await.0, StatusCode::BAD_REQUEST);
        let bad_type = json!({"protocol": 2, "projects": [{"name": "P", "todos": [1]}]}).to_string();
        assert_eq!(call(app.clone(), post(TOKEN, bad_type)).await.0, StatusCode::BAD_REQUEST);

        let too_deep = json!({"protocol": 2, "projects": [{"name": "1", "children": [{"name": "2", "children": [
            {"name": "3", "children": [{"name": "4", "todos": ["x"]}]}
        ]}]}]})
        .to_string();
        assert_eq!(call(app.clone(), post(TOKEN, too_deep)).await.0, StatusCode::BAD_REQUEST);

        let big = json!({"protocol": 2, "projects": [{"name": "P", "todos": ["x".repeat(MAX_BODY_BYTES + 1)]}]}).to_string();
        let (s, v) = call(app.clone(), post(TOKEN, big)).await;
        assert_eq!(s, StatusCode::PAYLOAD_TOO_LARGE, "{v}");

        let (s, v) = call(app.clone(), Request::get("/nope").body(Body::empty()).unwrap()).await;
        assert_eq!(s, StatusCode::NOT_FOUND);
        assert!(v["error"].is_string());

        let (s, v) = call(app, Request::get("/api/v2/todos").body(Body::empty()).unwrap()).await;
        assert_eq!(s, StatusCode::METHOD_NOT_ALLOWED);
        assert!(v["error"].is_string(), "405 也应返回 JSON 错误体");

        assert!(log.lock().unwrap().is_empty());
        assert_eq!(std::fs::read_dir(tmp.path()).unwrap().count(), 0);
    }
}
