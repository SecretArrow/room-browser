//! Secret redaction — port of the Android `SecretRedaction` /
//! DevTools redaction lists: header names, query keys, URL forms and the
//! `<redacted>` marker, applied at data-capture time.
//!
//! `redact` mirrors Android `SecretRedaction.redact` stage for stage:
//! header pairs on a line, JSON header pairs, query pairs, JWTs, PEM
//! private-key blocks, labelled mnemonics, and 64/128-char hex keys — plus
//! the desktop-only bearer/basic token rule that predates the port. Header
//! NAMES always survive (the fact a bug report needs); only values are
//! masked.
//!
//! Everything is matched over raw bytes with ASCII-only patterns and ASCII
//! anchors, so multibyte input can never split a character or panic: every
//! copied span starts and ends at positions fixed by ASCII bytes, and
//! non-ASCII bytes only ever travel inside copied spans. The Android
//! `(?i)`/`\b`/lookaround semantics are reproduced explicitly (word =
//! `[A-Za-z0-9_]`; `[A-Z ]` runs resolved longest-first, matching greedy
//! backtracking; maximal hex runs replicate the lookaround boundaries).

pub const REDACTED: &str = "<redacted>";

/// Header names whose VALUES are always redacted (case-insensitive).
/// Superset of the Android list (Android's `apikey`/`www-authenticate`
/// included) — a superset only ever masks more, never less.
pub const SECRET_HEADERS: &[&str] = &[
    "authorization", "cookie", "set-cookie", "x-api-key", "api-key",
    "x-auth-token", "x-access-token", "proxy-authorization", "apikey",
    "www-authenticate", "x-csrf-token", "x-xsrf-token", "x-session-id",
    "x-amz-security-token", "secret", "token", "password",
];

/// Query-string keys whose VALUES are redacted (case-insensitive).
/// Superset of the Android list (Android's `session` included).
pub const SECRET_QUERY_KEYS: &[&str] = &[
    "token", "access_token", "refresh_token", "id_token", "api_key",
    "apikey", "key", "secret", "password", "passwd", "signature", "sig",
    "sessionid", "session_id", "session", "auth", "code",
];

pub fn is_secret_header(name: &str) -> bool {
    let n = name.trim().to_lowercase();
    SECRET_HEADERS.contains(&n.as_str())
}

pub fn is_secret_query_key(key: &str) -> bool {
    let k = key.trim().to_lowercase();
    SECRET_QUERY_KEYS.contains(&k.as_str())
}

/// Redacts userinfo and secret query values in a URL.
pub fn redact_url(url: &str) -> String {
    let (scheme_rest, sep, rest) = match url.find("://") {
        Some(i) => (&url[..i + 3], i + 3, &url[i + 3..]),
        None => ("", 0, url),
    };
    let auth_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let auth = &rest[..auth_end];
    let tail = &rest[auth_end..];
    let mut auth_out = auth.to_string();
    if let Some(at) = auth.rfind('@') {
        auth_out = format!("{}@{}", crate::redaction::REDACTED, &auth[at + 1..]);
    }
    // walk the query string, redacting secret values
    let mut tail_out = String::new();
    let mut tail_rest = tail;
    if let Some(q) = tail_rest.find('?') {
        tail_out.push_str(&tail_rest[..=q]);
        tail_rest = &tail_rest[q + 1..];
        let mut first = true;
        let fragment = match tail_rest.find('#') {
            Some(f) => {
                let frag = &tail_rest[f..];
                tail_rest = &tail_rest[..f];
                frag
            }
            None => "",
        };
        for pair in tail_rest.split('&') {
            if !first {
                tail_out.push('&');
            }
            first = false;
            if pair.is_empty() {
                continue;
            }
            match pair.split_once('=') {
                Some((k, v)) => {
                    if is_secret_query_key(k) {
                        tail_out.push_str(&format!("{k}={REDACTED}"));
                    } else {
                        tail_out.push_str(&format!("{k}={v}"));
                    }
                }
                None => tail_out.push_str(pair),
            }
        }
        tail_out.push_str(fragment);
    } else {
        tail_out.push_str(tail_rest);
    }
    if sep == 0 {
        format!("{auth_out}{tail_out}")
    } else {
        format!("{scheme_rest}{auth_out}{tail_out}")
    }
}

/// Redacts secrets inside free text (used by transcript export over the
/// WHOLE document, like Android's `SecretRedaction.redact`). Stages run in
/// the Android order; the bearer/basic rule runs first (desktop-only extra).
pub fn redact(text: &str) -> String {
    if text.is_empty() {
        return text.to_string();
    }
    let bytes = text.as_bytes();
    let out = redact_bearer_basic(bytes);
    let out = mask_header_values(&out);
    let out = redact_header_json(&out);
    let out = redact_query_pairs(&out);
    let out = redact_jwt(&out);
    let out = redact_pem(&out);
    let out = redact_labelled_mnemonic(&out);
    let out = redact_key_hex(&out);
    // All edits happen at ASCII-fixed byte positions, so the buffer is
    // still valid UTF-8; the fallback keeps the function total anyway.
    String::from_utf8(out).unwrap_or_else(|_| text.to_string())
}

fn is_word(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

fn is_name_char(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'-' || b == b'_'
}

fn is_token_char(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.' || b == b'='
}

fn is_hex(b: u8) -> bool {
    b.is_ascii_hexdigit()
}

fn is_space_or_tab(b: u8) -> bool {
    b == b' ' || b == b'\t'
}

/// Java/Kotlin `\s`: space, tab, LF, VT, FF, CR — used verbatim so value
/// spans match the Android pattern exactly.
fn is_regex_space(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | 0x0B | 0x0C | b'\r')
}

/// UTF-8 sequence length at a known lead byte, clamped to the buffer.
fn utf8_len_at(bytes: &[u8], i: usize) -> usize {
    let b = bytes[i];
    let n = if b >= 0xF0 {
        4
    } else if b >= 0xE0 {
        3
    } else if b >= 0xC0 {
        2
    } else {
        1
    };
    if i + n > bytes.len() {
        bytes.len() - i
    } else {
        n
    }
}

fn find_sub(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || needle.len() > hay.len() {
        return None;
    }
    (0..=hay.len() - needle.len()).find(|&p| &hay[p..p + needle.len()] == needle)
}

/// Bearer / Basic tokens: the desktop rule that predates the Android port.
/// The search runs over an `to_ascii_lowercase` copy (byte-for-byte 1:1, so
/// offsets stay valid for the original) — never over `to_lowercase`, which
/// can shift byte offsets on input like `İ`.
fn redact_bearer_basic(bytes: &[u8]) -> Vec<u8> {
    let lower = bytes.to_ascii_lowercase();
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut i = 0;
    while i < bytes.len() {
        let rest = &lower[i..];
        let hit = find_sub(rest, b"bearer ")
            .map(|p| (p, 7))
            .or_else(|| find_sub(rest, b"basic ").map(|p| (p, 6)));
        match hit {
            None => {
                out.extend_from_slice(&bytes[i..]);
                break;
            }
            Some((rel, mlen)) => {
                let abs = i + rel;
                let after = abs + mlen;
                out.extend_from_slice(&bytes[i..after]);
                let mut take = 0;
                while after + take < bytes.len() && is_token_char(bytes[after + take]) {
                    take += 1;
                }
                if take >= 8 {
                    out.extend_from_slice(REDACTED.as_bytes());
                    i = after + take;
                } else if after < bytes.len() {
                    // Short token: keep it, advancing one full character
                    // (the next char may be multibyte).
                    let n = utf8_len_at(bytes, after);
                    out.extend_from_slice(&bytes[after..after + n]);
                    i = after + n;
                } else {
                    i = after;
                }
            }
        }
    }
    out
}

/// A `name:` pair wherever it sits on the line (Android `HEADER_PAIR`).
/// Returns (marker_end, name_start, name_len); the marker starts at `pos`.
fn match_header_pair(line: &[u8], pos: usize) -> Option<(usize, usize, usize)> {
    let mut p = pos;
    if line[p] == b'"' {
        p += 1;
    }
    let name_start = p;
    let mut max_name = 0;
    while max_name < 40
        && name_start + max_name < line.len()
        && is_name_char(line[name_start + max_name])
    {
        max_name += 1;
    }
    if max_name == 0 {
        return None;
    }
    // Greedy {1,40}: longest name first, backtracking shorter on failure —
    // the optional closing quote, the space run and the colon are re-tried
    // for every name length, matching regex backtracking.
    for nl in (1..=max_name).rev() {
        let mut j = name_start + nl;
        if j < line.len() && line[j] == b'"' {
            j += 1;
        }
        while j < line.len() && is_space_or_tab(line[j]) {
            j += 1;
        }
        if j < line.len() && line[j] == b':' {
            j += 1;
            while j < line.len() && is_space_or_tab(line[j]) {
                j += 1;
            }
            return Some((j, name_start, nl));
        }
    }
    None
}

/// Android `maskHeaderValues`: per line, a credential pair's VALUE runs to
/// the next pair marker (secret or not) and is replaced by the mask; the
/// name and everything before it survive.
fn mask_line(line: &[u8]) -> Vec<u8> {
    let mut marks: Vec<(usize, usize, bool)> = Vec::new();
    let mut pos = 0;
    while pos < line.len() {
        if pos > 0 && is_word(line[pos - 1]) {
            pos += 1;
            continue;
        }
        if let Some((end, name_start, name_len)) = match_header_pair(line, pos) {
            let name = &line[name_start..name_start + name_len];
            let secret = SECRET_HEADERS
                .iter()
                .any(|h| h.as_bytes().eq_ignore_ascii_case(name));
            marks.push((pos, end, secret));
            pos = end;
        } else {
            pos += 1;
        }
    }
    if marks.is_empty() {
        return line.to_vec();
    }
    let mut out = Vec::with_capacity(line.len() + REDACTED.len());
    let mut cursor = 0;
    for k in 0..marks.len() {
        let (_, mend, secret) = marks[k];
        let value_end = if k + 1 < marks.len() {
            marks[k + 1].0
        } else {
            line.len()
        };
        out.extend_from_slice(&line[cursor..mend]);
        if secret {
            out.extend_from_slice(REDACTED.as_bytes());
        } else {
            out.extend_from_slice(&line[mend..value_end]);
        }
        cursor = value_end;
    }
    out.extend_from_slice(&line[cursor..]);
    out
}

fn mask_header_values(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut start = 0;
    for idx in 0..=bytes.len() {
        if idx == bytes.len() || bytes[idx] == b'\n' {
            out.extend_from_slice(&mask_line(&bytes[start..idx]));
            if idx < bytes.len() {
                out.push(b'\n');
                start = idx + 1;
            }
        }
    }
    out
}

/// Android `HEADER_JSON`: `"authorization": "..."`-style pairs inside a
/// one-line JSON object; the quoted value is masked.
fn redact_header_json(bytes: &[u8]) -> Vec<u8> {
    const NAMES: [&[u8]; 7] = [
        b"authorization",
        b"cookie",
        b"set-cookie",
        b"x-api-key",
        b"api-key",
        b"x-auth-token",
        b"x-csrf-token",
    ];
    let lower = bytes.to_ascii_lowercase();
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut i = 0;
    'outer: while i < bytes.len() {
        if lower[i] == b'"' {
            for name in NAMES {
                let j = i + 1;
                if lower[j..].starts_with(name) && lower.get(j + name.len()) == Some(&b'"') {
                    let mut k = j + name.len() + 1;
                    while k < lower.len() && is_space_or_tab(lower[k]) {
                        k += 1;
                    }
                    if lower.get(k) == Some(&b':') {
                        k += 1;
                        while k < lower.len() && is_space_or_tab(lower[k]) {
                            k += 1;
                        }
                        if lower.get(k) == Some(&b'"') {
                            if let Some(f) = lower[k + 1..].iter().position(|&c| c == b'"') {
                                let vstart = k + 1;
                                let vend = vstart + f;
                                out.extend_from_slice(&bytes[i..vstart]);
                                out.extend_from_slice(REDACTED.as_bytes());
                                i = vend + 1; // consume the closing quote
                                continue 'outer;
                            }
                        }
                    }
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

/// Android `QUERY_PAIR`: `[?&]name=value`; the name is kept, a credential
/// name's value is masked. Values need at least one character.
fn redact_query_pairs(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut i = 0;
    while i < bytes.len() {
        let c = bytes[i];
        if c != b'?' && c != b'&' {
            out.push(c);
            i += 1;
            continue;
        }
        let mut j = i + 1;
        let name_start = j;
        while j < bytes.len() && (bytes[j].is_ascii_alphanumeric() || bytes[j] == b'_') {
            j += 1;
        }
        if j == name_start || j >= bytes.len() || bytes[j] != b'=' {
            out.push(c);
            i += 1;
            continue;
        }
        let eq = j;
        j += 1;
        let value_start = j;
        while j < bytes.len() {
            let vc = bytes[j];
            if vc == b'&'
                || is_regex_space(vc)
                || vc == b'"'
                || vc == b'\''
                || vc == b'#'
                || vc == b'<'
                || vc == b'>'
                || vc == b'\\'
            {
                break;
            }
            j += 1;
        }
        if j == value_start {
            // No value characters: the Android match fails entirely.
            out.push(c);
            i += 1;
            continue;
        }
        let name = &bytes[name_start..eq];
        if SECRET_QUERY_KEYS
            .iter()
            .any(|k| k.as_bytes().eq_ignore_ascii_case(name))
        {
            out.extend_from_slice(&bytes[i..value_start]);
            out.extend_from_slice(REDACTED.as_bytes());
        } else {
            out.extend_from_slice(&bytes[i..j]);
        }
        i = j;
    }
    out
}

/// `[A-Za-z0-9_-]*` run end, used for JWT segments.
fn jwt_class_run(bytes: &[u8], mut p: usize) -> usize {
    while p < bytes.len() && (bytes[p].is_ascii_alphanumeric() || bytes[p] == b'-' || bytes[p] == b'_')
    {
        p += 1;
    }
    p
}

/// End offset of a `\beyJ…\b` JWT match starting at `start`, or None.
/// Segments are the dot-separated `[A-Za-z0-9_-]{4,}` runs; the final run
/// shrinks (regex backtracking) until its end boundary holds.
fn jwt_end(bytes: &[u8], start: usize) -> Option<usize> {
    let mut p = start + 3;
    let seg1_end = jwt_class_run(bytes, p);
    if seg1_end - p < 4 || seg1_end >= bytes.len() || bytes[seg1_end] != b'.' {
        return None;
    }
    p = seg1_end + 1;
    let seg2_start = p;
    let seg2_end = jwt_class_run(bytes, p);
    if seg2_end - seg2_start < 4 || seg2_end >= bytes.len() || bytes[seg2_end] != b'.' {
        return None;
    }
    p = seg2_end + 1;
    let seg3_start = p;
    let seg3_max = jwt_class_run(bytes, p);
    if seg3_max - seg3_start < 4 {
        return None;
    }
    let mut e = seg3_max;
    while e >= seg3_start + 4 {
        let last = bytes[e - 1];
        let boundary = if is_word(last) {
            e == bytes.len() || !is_word(bytes[e])
        } else {
            e < bytes.len() && is_word(bytes[e])
        };
        if boundary {
            return Some(e);
        }
        e -= 1;
    }
    None
}

/// Android `JWT`: three base64url segments whose header decodes to `{"…`.
fn redact_jwt(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'e'
            && (i == 0 || !is_word(bytes[i - 1]))
            && bytes[i..].starts_with(b"eyJ")
        {
            if let Some(end) = jwt_end(bytes, i) {
                out.extend_from_slice(REDACTED.as_bytes());
                i = end;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

/// Android `pemMasked`: the markers stay, the key material between them
/// goes. `lines()` splits on LF (CR trimmed) as on the JVM.
/// JVM `lines()` splits on LF and trims one trailing CR per line.
fn trim_cr(l: &[u8]) -> &[u8] {
    if l.last() == Some(&b'\r') {
        &l[..l.len() - 1]
    } else {
        l
    }
}

fn pem_masked(block: &[u8]) -> String {
    let mut lines: Vec<&[u8]> = Vec::new();
    let mut s = 0;
    for idx in 0..block.len() {
        if block[idx] == b'\n' {
            lines.push(&block[s..idx]);
            s = idx + 1;
        }
    }
    lines.push(&block[s..]);
    if lines.len() <= 2 {
        return REDACTED.to_string();
    }
    let mut out = String::new();
    out.push_str(&String::from_utf8_lossy(trim_cr(lines[0])));
    out.push('\n');
    out.push_str(REDACTED);
    out.push('\n');
    out.push_str(&String::from_utf8_lossy(trim_cr(lines[lines.len() - 1])));
    out
}

/// The longest `[A-Z ]*` run start positions, resolved longest-first so the
/// trailing `PRIVATE KEY-----` literal can consume what greedy backtracking
/// would give it. Returns the literal end on success.
fn ascii_upper_run_then(
    bytes: &[u8],
    run_start: usize,
    literal: &[u8],
) -> Option<usize> {
    let mut run_end = run_start;
    while run_end < bytes.len() && (bytes[run_end].is_ascii_uppercase() || bytes[run_end] == b' ') {
        run_end += 1;
    }
    for k in (run_start..=run_end).rev() {
        if bytes[k..].starts_with(literal) {
            return Some(k + literal.len());
        }
    }
    None
}

/// Android `PEM_BLOCK`: `-----BEGIN [A-Z ]*PRIVATE KEY-----` through the
/// first following `-----END [A-Z ]*PRIVATE KEY-----`.
fn redact_pem(bytes: &[u8]) -> Vec<u8> {
    const BEGIN: &[u8] = b"-----BEGIN ";
    const END_HEAD: &[u8] = b"-----END ";
    const END_LIT: &[u8] = b"PRIVATE KEY-----";
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i..].starts_with(BEGIN) {
            if let Some(lit_end) = ascii_upper_run_then(bytes, i + BEGIN.len(), END_LIT) {
                // Non-greedy body: the first END marker after the BEGIN.
                let mut f = lit_end;
                let mut matched = None;
                while f + END_HEAD.len() <= bytes.len() {
                    if bytes[f..].starts_with(END_HEAD) {
                        if let Some(e) = ascii_upper_run_then(bytes, f + END_HEAD.len(), END_LIT) {
                            matched = Some(e);
                            break;
                        }
                    }
                    f += 1;
                }
                if let Some(block_end) = matched {
                    let masked = pem_masked(&bytes[i..block_end]);
                    out.extend_from_slice(masked.as_bytes());
                    i = block_end;
                    continue;
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

/// Android `LABELLED_MNEMONIC`: twelve or more lowercase words after an
/// explicit seed/mnemonic label; the label and its separator survive.
fn match_mnemonic(bytes: &[u8], lower: &[u8], i: usize, label: &[u8]) -> Option<(usize, usize)> {
    if !lower[i..].starts_with(label) {
        return None;
    }
    let mut j = i + label.len();
    if j < bytes.len() && is_word(bytes[j]) {
        return None; // \b after the label
    }
    while j < bytes.len() && is_space_or_tab(bytes[j]) {
        j += 1;
    }
    if j >= bytes.len() || (bytes[j] != b':' && bytes[j] != b'=') {
        return None;
    }
    j += 1;
    while j < bytes.len() && is_space_or_tab(bytes[j]) {
        j += 1;
    }
    let words_start = j;
    let mut count = 0;
    let mut words_end = j;
    loop {
        let word_start = j;
        while j < bytes.len() && bytes[j].is_ascii_lowercase() {
            j += 1;
        }
        if j - word_start < 3 {
            break;
        }
        count += 1;
        words_end = j;
        // separator before the next word
        let sep_start = j;
        while j < bytes.len() && is_space_or_tab(bytes[j]) {
            j += 1;
        }
        if j == sep_start {
            break;
        }
    }
    if count >= 12 {
        Some((words_start, words_end))
    } else {
        None
    }
}

fn redact_labelled_mnemonic(bytes: &[u8]) -> Vec<u8> {
    const LABELS: [&[u8]; 4] = [
        b"seed phrase",
        b"mnemonic",
        b"recovery phrase",
        b"secret phrase",
    ];
    let lower = bytes.to_ascii_lowercase();
    let mut out = Vec::with_capacity(bytes.len() + REDACTED.len());
    let mut i = 0;
    'outer: while i < bytes.len() {
        if i == 0 || !is_word(bytes[i - 1]) {
            for label in LABELS {
                if let Some((ws, we)) = match_mnemonic(bytes, &lower, i, label) {
                    out.extend_from_slice(&bytes[i..ws]);
                    out.extend_from_slice(REDACTED.as_bytes());
                    i = we;
                    continue 'outer;
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

/// A raw 32-byte key or a 64-byte seed, and nothing merely long: maximal
/// hex runs of exactly 64 or 128 characters (run edges replicate the
/// Android lookaround boundaries).
fn redact_key_hex(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if is_hex(bytes[i]) {
            let s = i;
            while i < bytes.len() && is_hex(bytes[i]) {
                i += 1;
            }
            let n = i - s;
            if n == 64 || n == 128 {
                out.extend_from_slice(REDACTED.as_bytes());
            } else {
                out.extend_from_slice(&bytes[s..i]);
            }
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    out
}

/// Redacts header values in a header map (used by the network panel at
/// capture time).
pub fn redact_headers<'a>(headers: impl Iterator<Item = (String, String)>) -> Vec<(String, String)> {
    headers
        .map(|(k, v)| {
            if is_secret_header(&k) {
                (k, REDACTED.to_string())
            } else {
                (k, v)
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_redaction() {
        assert_eq!(
            redact_url("https://user:pass@example.com/a?token=abc&q=1"),
            "https://<redacted>@example.com/a?token=<redacted>&q=1"
        );
        assert_eq!(
            redact_url("https://example.com/p?api_key=zzz&x=2#frag"),
            "https://example.com/p?api_key=<redacted>&x=2#frag"
        );
        assert_eq!(redact_url("https://example.com/p?safe=1"), "https://example.com/p?safe=1");
    }

    #[test]
    fn header_redaction() {
        assert!(is_secret_header("Authorization"));
        assert!(is_secret_header("X-API-KEY"));
        assert!(!is_secret_header("Content-Type"));
        let red = redact_headers(
            vec![
                ("Authorization".into(), "Bearer xyz".into()),
                ("Content-Type".into(), "text/html".into()),
            ]
            .into_iter(),
        );
        assert_eq!(red[0].1, REDACTED);
        assert_eq!(red[1].1, "text/html");
    }

    #[test]
    fn text_redaction() {
        let out = redact("curl -H 'Authorization: Bearer abc123def456abc123def456' https://x.com");
        assert!(out.contains(REDACTED));
        assert!(!out.contains("abc123def456"));
        // short tokens (<8 chars) are left alone
        let keep = redact("bearer abcd done");
        assert!(keep.contains("abcd"));
    }

    // --------------------------------------------- Android-parity cases

    #[test]
    fn authorization_header_value_is_masked_and_the_name_survives() {
        let redacted = redact("Authorization: Bearer FAKE-NOT-A-REAL-TOKEN");
        assert!(redacted.contains("Authorization:"));
        assert!(!redacted.contains("FAKE-NOT-A-REAL-TOKEN"));
        assert!(redacted.contains(REDACTED));
    }

    #[test]
    fn cookie_and_set_cookie_values_are_masked() {
        let cookies = redact("Cookie: sid=FAKE-SESSION-VALUE");
        assert!(cookies.contains("Cookie:"));
        assert!(!cookies.contains("FAKE-SESSION-VALUE"));
        let set_cookie = redact("Set-Cookie: sid=FAKE-SESSION-VALUE; HttpOnly");
        assert!(set_cookie.contains("Set-Cookie:"));
        assert!(!set_cookie.contains("FAKE-SESSION-VALUE"));
    }

    #[test]
    fn api_key_header_is_masked_whatever_its_case() {
        for name in ["X-Api-Key", "x-api-key", "API-KEY"] {
            let redacted = redact(&format!("{name}: FAKE-API-KEY-0000"));
            assert!(!redacted.contains("FAKE-API-KEY-0000"), "{name}: {redacted}");
            assert!(redacted.contains(name));
        }
    }

    #[test]
    fn a_credential_pair_after_a_log_prefix_is_masked_too() {
        let redacted = redact("I/chromium: Authorization: Bearer FAKE-NOT-A-REAL-TOKEN");
        assert_eq!(redacted, format!("I/chromium: Authorization: {REDACTED}"));
        assert_eq!(redact(&redacted), redacted);
    }

    #[test]
    fn every_credential_pair_on_a_line_is_masked() {
        let redacted = redact("console: a: b Cookie: FAKE-SESSION-VALUE Set-Cookie: FAKE-OTHER");
        assert!(!redacted.contains("FAKE-SESSION-VALUE"));
        assert!(!redacted.contains("FAKE-OTHER"));
        assert!(redacted.contains("console: a: b Cookie:"));
        assert!(redacted.contains("Set-Cookie:"));
    }

    #[test]
    fn a_prefix_does_not_turn_the_next_ordinary_header_into_a_secret() {
        let line = "Response: Content-Type: text/html; charset=utf-8";
        assert_eq!(redact(line), line);
    }

    #[test]
    fn token_and_key_query_values_are_masked_but_other_parameters_are_not() {
        let redacted = redact("GET https://example.test/path?page=2&token=FAKE-TOKEN&key=FAKE-KEY&sort=asc");
        assert!(!redacted.contains("FAKE-TOKEN"));
        assert!(!redacted.contains("FAKE-KEY"));
        assert!(redacted.contains("page=2"));
        assert!(redacted.contains("sort=asc"));
        assert!(redacted.contains("token="));
    }

    #[test]
    fn a_jwt_shaped_string_is_masked() {
        let jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.FAKE-SIGNATURE-NOT-REAL";
        let redacted = redact(&format!("session: {jwt}"));
        assert!(!redacted.contains(jwt));
        assert!(redacted.contains(REDACTED));
    }

    #[test]
    fn a_pem_private_key_body_is_masked_and_its_markers_survive() {
        let pem = "-----BEGIN PRIVATE KEY-----\nFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE\n-----END PRIVATE KEY-----";
        let redacted = redact(pem);
        assert!(redacted.contains("-----BEGIN PRIVATE KEY-----"));
        assert!(redacted.contains("-----END PRIVATE KEY-----"));
        assert!(!redacted.contains("FAKEFAKEFAKEFAKE"));
        assert!(redacted.contains(REDACTED));
    }

    #[test]
    fn a_labelled_seed_phrase_is_masked() {
        let phrase = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima";
        let redacted = redact(&format!("seed phrase: {phrase}"));
        assert!(!redacted.contains("foxtrot"));
        assert!(redacted.contains("seed phrase:"));
        // eleven words is one short of a mnemonic
        let short = redact("seed phrase: alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo");
        assert!(short.contains("kilo"));
    }

    #[test]
    fn ordinary_headers_keep_both_their_name_and_their_value() {
        let line = "Content-Type: text/html; charset=utf-8";
        assert_eq!(redact(line), line);
    }

    #[test]
    fn ordinary_prose_is_untouched() {
        let prose = "The user asked for a summary of the article about seed banks and passwords.";
        assert_eq!(redact(prose), prose);
    }

    #[test]
    fn a_short_hex_string_is_not_treated_as_key_material() {
        let sha = "0f1e2d3c4b5a69788796a5b4c3d2e1f0abcd1234";
        assert!(redact(&format!("commit {sha}")).contains(sha));
    }

    #[test]
    fn long_hex_keys_are_masked() {
        let key = "0f1e2d3c4b5a69788796a5b4c3d2e1f0abcd12340f1e2d3c4b5a69788796a5b4";
        assert_eq!(key.len(), 64);
        assert!(!redact(&format!("key {key}")).contains(key));
    }

    #[test]
    fn a_bare_base64_prefix_is_not_treated_as_a_jwt() {
        let not_a_jwt = "eyJhbGciOiJIUzI1NiJ9-not-a-token";
        assert_eq!(redact(not_a_jwt), not_a_jwt);
    }

    #[test]
    fn a_url_with_no_credential_survives_intact() {
        let url = "https://example.test/docs/getting-started?lang=en#install";
        assert_eq!(redact(url), url);
    }

    #[test]
    fn redaction_is_idempotent() {
        let once = redact("Authorization: Bearer FAKE-NOT-A-REAL-TOKEN");
        assert_eq!(redact(&once), once);
        let twice_input = "console: a: b Cookie: FAKE-SESSION-VALUE Set-Cookie: FAKE-OTHER";
        let twice = redact(twice_input);
        assert_eq!(redact(&twice), twice);
    }

    // --------------------------------------------- multibyte safety

    #[test]
    fn multibyte_text_never_panics_and_never_splits_a_character() {
        // The old implementation lowercased with to_lowercase(), whose
        // output can shift byte offsets (İ is 2 bytes, its lowercase form
        // 3) — this exact input used to panic.
        let out = redact("İBearer abc123def456abc123def456");
        assert_eq!(out, "İBearer <redacted>");
        let jp = redact("メモ bearer abc123def456abc123def456 です");
        assert!(jp.contains("メモ"));
        assert!(jp.contains("です"));
        assert!(!jp.contains("abc123def456"));
        let emoji = redact("🔐 basic abc123def456abc123def456");
        assert!(emoji.contains("🔐"));
        assert!(!emoji.contains("abc123def456"));
        // Short token right before a multibyte char: the char must survive
        // whole.
        let keep = redact("bearer ab İ");
        assert_eq!(keep, "bearer ab İ");
        // A header pair whose value contains multibyte text.
        let mixed = redact("Authorization: メモ fake-value-123456");
        assert!(!mixed.contains("fake-value-123456"));
        assert!(mixed.contains("Authorization:"));
    }
}
