//! Minimal HTTP service wrapping the payslip STARK engine (src/stark.rs) so a non-Rust
//! caller -- specifically banking-dilithium's Spring Boot backend -- can request a proof
//! and later verify one over a plain JSON/HTTP contract, without needing any Rust FFI/JNI.
//!
//! Endpoints:
//!   GET  /health                         -> 200 {"status":"ok"}
//!   POST /prove   {earningsCents:[..], deductionsCents:[..]}
//!                                         -> 200 {netPayCents, numEntries, proofBase64, proofSizeBytes}
//!                                         -> 400 {"error": "..."}  on bad input (e.g. too many entries)
//!   POST /verify  {proofBase64, netPayCents, numEntries}
//!                                         -> 200 {"valid": true}
//!                                         -> 200 {"valid": false, "error": "..."}  (proof rejected)
//!                                         -> 400 {"error": "..."}  on malformed request
//!
//! Deliberately single-purpose and dependency-light (tiny_http, not a full async web
//! framework) since this service does exactly one job for one caller.

mod stark;

use serde::{Deserialize, Serialize};
use tiny_http::{Header, Method, Response, Server};

#[derive(Deserialize)]
struct ProveRequest {
    #[serde(rename = "earningsCents")]
    earnings_cents: Vec<u64>,
    #[serde(rename = "deductionsCents")]
    deductions_cents: Vec<u64>,
}

#[derive(Serialize)]
struct ProveResponse {
    #[serde(rename = "netPayCents")]
    net_pay_cents: u64,
    #[serde(rename = "numEntries")]
    num_entries: usize,
    #[serde(rename = "proofBase64")]
    proof_base64: String,
    #[serde(rename = "proofSizeBytes")]
    proof_size_bytes: usize,
}

#[derive(Deserialize)]
struct VerifyRequest {
    #[serde(rename = "proofBase64")]
    proof_base64: String,
    #[serde(rename = "netPayCents")]
    net_pay_cents: u64,
    #[serde(rename = "numEntries")]
    num_entries: usize,
}

#[derive(Serialize)]
struct VerifyResponse {
    valid: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

fn json_response(status: u16, body: &impl Serialize) -> Response<std::io::Cursor<Vec<u8>>> {
    let payload = serde_json::to_string(body).unwrap_or_else(|_| "{\"error\":\"serialization failure\"}".into());
    let header = Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap();
    Response::from_string(payload).with_status_code(status).with_header(header)
}

fn handle_prove(body: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    let req: ProveRequest = match serde_json::from_str(body) {
        Ok(r) => r,
        Err(e) => return json_response(400, &ErrorResponse { error: format!("invalid JSON: {e}") }),
    };
    match stark::prove(&req.earnings_cents, &req.deductions_cents) {
        Ok(result) => json_response(
            200,
            &ProveResponse {
                net_pay_cents: result.net_pay_cents,
                num_entries: result.num_entries,
                proof_size_bytes: result.proof_bytes.len(),
                proof_base64: base64_encode(&result.proof_bytes),
            },
        ),
        Err(e) => json_response(400, &ErrorResponse { error: e.to_string() }),
    }
}

fn handle_verify(body: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    let req: VerifyRequest = match serde_json::from_str(body) {
        Ok(r) => r,
        Err(e) => return json_response(400, &ErrorResponse { error: format!("invalid JSON: {e}") }),
    };
    let proof_bytes = match base64_decode(&req.proof_base64) {
        Ok(b) => b,
        Err(e) => return json_response(400, &ErrorResponse { error: format!("invalid base64: {e}") }),
    };
    match stark::verify(&proof_bytes, req.net_pay_cents, req.num_entries) {
        Ok(()) => json_response(200, &VerifyResponse { valid: true, error: None }),
        Err(e) => json_response(200, &VerifyResponse { valid: false, error: Some(e.to_string()) }),
    }
}

fn main() {
    let addr = std::env::var("PROOF_SERVICE_ADDR").unwrap_or_else(|_| "0.0.0.0:7878".to_string());
    let server = Server::http(&addr).expect("failed to bind HTTP server");
    println!("payslip zk-STARK proof service listening on {addr}");

    for mut request in server.incoming_requests() {
        let method = request.method().clone();
        let url = request.url().to_string();

        let mut body = String::new();
        if let Err(e) = request.as_reader().read_to_string(&mut body) {
            let _ = request.respond(json_response(400, &ErrorResponse { error: format!("failed to read body: {e}") }));
            continue;
        }

        let response = match (&method, url.as_str()) {
            (Method::Get, "/health") => json_response(200, &serde_json::json!({"status": "ok"})),
            (Method::Post, "/prove") => handle_prove(&body),
            (Method::Post, "/verify") => handle_verify(&body),
            _ => json_response(404, &ErrorResponse { error: format!("no route for {method:?} {url}") }),
        };

        let _ = request.respond(response);
    }
}

// --- tiny base64 codec (standard alphabet, no external dependency needed for this) ---

const B64_ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn base64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0];
        let b1 = *chunk.get(1).unwrap_or(&0);
        let b2 = *chunk.get(2).unwrap_or(&0);
        out.push(B64_ALPHABET[(b0 >> 2) as usize] as char);
        out.push(B64_ALPHABET[(((b0 & 0x03) << 4) | (b1 >> 4)) as usize] as char);
        out.push(if chunk.len() > 1 { B64_ALPHABET[(((b1 & 0x0f) << 2) | (b2 >> 6)) as usize] as char } else { '=' });
        out.push(if chunk.len() > 2 { B64_ALPHABET[(b2 & 0x3f) as usize] as char } else { '=' });
    }
    out
}

fn base64_decode(s: &str) -> Result<Vec<u8>, String> {
    fn val(c: u8) -> Result<u8, String> {
        match c {
            b'A'..=b'Z' => Ok(c - b'A'),
            b'a'..=b'z' => Ok(c - b'a' + 26),
            b'0'..=b'9' => Ok(c - b'0' + 52),
            b'+' => Ok(62),
            b'/' => Ok(63),
            _ => Err(format!("invalid base64 character: {}", c as char)),
        }
    }
    let clean: Vec<u8> = s.bytes().filter(|&b| b != b'=' && !b.is_ascii_whitespace()).collect();
    let mut out = Vec::with_capacity(clean.len() * 3 / 4);
    for chunk in clean.chunks(4) {
        let mut vals = [0u8; 4];
        for (i, &c) in chunk.iter().enumerate() {
            vals[i] = val(c)?;
        }
        out.push((vals[0] << 2) | (vals[1] >> 4));
        if chunk.len() > 2 {
            out.push((vals[1] << 4) | (vals[2] >> 2));
        }
        if chunk.len() > 3 {
            out.push((vals[2] << 6) | vals[3]);
        }
    }
    Ok(out)
}
