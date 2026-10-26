//! Omnibox URL heuristics — full port of `rb_url.c` (UrlIntelligence).

/// The schemes that count as "known" for `has_scheme`.
const SCHEMES: &[&str] = &[
    "about", "blob", "brave", "chrome", "data", "file", "ftp", "http",
    "https", "javascript", "mailto", "tel", "view-source", "ws", "wss",
];

fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{b}' | '\u{c}' | '\r')
}

fn has_ws(s: &str) -> bool {
    s.chars().any(is_ws)
}

fn ci_eq(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b)
}

fn ci_starts(s: &str, prefix: &str) -> bool {
    // Byte-slice comparison: indexing a `&[u8]` has no char-boundary
    // requirement, so multibyte input can never panic here (M-8), and the
    // ASCII-case-insensitive result is identical to the old str slice.
    let (sb, pb) = (s.as_bytes(), prefix.as_bytes());
    sb.len() >= pb.len() && sb[..pb.len()].eq_ignore_ascii_case(pb)
}

/// Length of a `scheme:` prefix (alpha first char, then alnum/+/-/. then ':'),
/// or 0.
fn scheme_prefix_len(s: &str) -> usize {
    let b = s.as_bytes();
    if b.is_empty() || !b[0].is_ascii_alphabetic() {
        return 0;
    }
    for (i, &c) in b.iter().enumerate().skip(1) {
        if c == b':' {
            return i + 1;
        }
        if !(c.is_ascii_alphanumeric() || c == b'+' || c == b'-' || c == b'.') {
            return 0;
        }
    }
    0
}

fn known_scheme(scheme: &str) -> bool {
    SCHEMES.iter().any(|x| ci_eq(scheme, x))
}

/// `scheme://` or a known bare scheme (`about:`, `mailto:`, ...).
pub fn has_scheme(s: &str) -> bool {
    let n = scheme_prefix_len(s);
    if n == 0 {
        return false;
    }
    if s[n..].starts_with("//") {
        return true;
    }
    known_scheme(&s[..n - 1])
}

/// Length of the authority component (up to / ? #).
fn auth_len(s: &str) -> usize {
    s.find(['/', '?', '#']).unwrap_or(s.len())
}

/// `rb_is_host_like`: dotted host with alphabetic tail, or a numeric-ish
/// address literal with enough separators.
fn is_host_like(s: &str) -> bool {
    let n = s.len();
    if n == 0 {
        return false;
    }
    let bytes = s.as_bytes();
    // Host part: up to ']' for bracketed IPv6, else up to ':'.
    let mut host_end = n;
    if bytes[0] == b'[' {
        if let Some(pos) = s.find(']') {
            host_end = pos;
        }
    } else if let Some(pos) = s.find(':') {
        if pos < host_end {
            host_end = pos;
        }
    }
    let host = &s[..host_end];
    if let Some(last_dot) = host.rfind('.') {
        let tail = &host[last_dot + 1..];
        if last_dot > 0 && !tail.is_empty() && tail.chars().all(|c| c.is_ascii_alphabetic()) {
            return true;
        }
    }
    // Numeric/IPv6-ish literal.
    let mut dots = 0;
    let mut colons = 0;
    let mut only_addr = true;
    for c in s.chars() {
        match c {
            '.' => dots += 1,
            ':' => colons += 1,
            '[' | ']' => {}
            _ if c.is_ascii_hexdigit() => {}
            _ => {
                only_addr = false;
                break;
            }
        }
    }
    only_addr && (dots >= 2 || colons >= 2 || (dots >= 1 && colons >= 1))
}

/// `rb_url_is_probably_url` — the pre-UrlIntelligence helper (ASCII only,
/// no interior whitespace).
pub fn is_probably_url(input: &str) -> bool {
    let t = input.trim_matches(is_ws);
    if t.is_empty() || t.len() >= 4096 {
        return false;
    }
    if !t.is_ascii() {
        return false;
    }
    if t.contains("://") {
        return true;
    }
    if has_scheme(t) {
        return true;
    }
    if ci_starts(t, "localhost") {
        // Safe: `ci_starts` just matched the 9 ASCII bytes "localhost" (and
        // the `is_ascii` guard above already filtered non-ASCII input), so
        // byte 9 is a char boundary.
        let next = t[9..].chars().next();
        return matches!(next, None | Some(':') | Some('/'));
    }
    is_host_like(&t[..auth_len(t)])
}

/// `rb_url_normalize`: schemeless input gets https://, bare authority gets
/// its trailing slash, and `host?query` / `host#frag` get the slash inserted.
pub fn normalize(input: &str) -> String {
    let t = input.trim_matches(is_ws);
    if t.is_empty() {
        return String::new();
    }
    let mut out = String::new();
    if !has_scheme(t) {
        out.push_str("https://");
    }
    out.push_str(t);
    if let Some(sep) = out.find("://") {
        let after = sep + 3;
        let rest = &out[after..];
        if let Some(pos) = rest.find(['/', '?', '#']) {
            if pos == 0 {
                // already has a path/query — nothing to do
                return out;
            }
            let cut = after + pos;
            let mut fixed = String::with_capacity(out.len() + 1);
            fixed.push_str(&out[..cut]);
            fixed.push('/');
            fixed.push_str(&out[cut..]);
            return fixed;
        }
        out.push('/');
    }
    out
}

/// `^(?:\d{1,3}\.){3}\d{1,3}(:\d+)?$` with octets ≤ 255.
fn looks_like_ipv4(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    for part in 0..4 {
        let start = i;
        let mut value: u32 = 0;
        while i < b.len() && b[i].is_ascii_digit() && i - start < 3 {
            value = value * 10 + (b[i] - b'0') as u32;
            i += 1;
        }
        let digits = i - start;
        if digits == 0 || value > 255 {
            return false;
        }
        if part < 3 {
            if i >= b.len() || b[i] != b'.' {
                return false;
            }
            i += 1;
        }
    }
    if i < b.len() && b[i] == b':' {
        i += 1;
        if i >= b.len() || !b[i].is_ascii_digit() {
            return false;
        }
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
    }
    i == b.len()
}

/// `^[0-9a-fA-F:]+:[0-9a-fA-F:.]+$` (callers already found a ':').
fn looks_like_ipv6(s: &str) -> bool {
    let run = s
        .chars()
        .take_while(|c| c.is_ascii_hexdigit() || *c == ':')
        .count();
    for j in (1..=run).rev() {
        if s.as_bytes()[j - 1] != b':' {
            continue;
        }
        let tail = &s[j..];
        if !tail.is_empty() && tail.chars().all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.') {
            return true;
        }
    }
    false
}

/// `^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(/.*)?$` case-insensitive.
fn looks_like_localhost(s: &str) -> bool {
    for name in ["localhost", "127.0.0.1", "[::1]"] {
        // Byte-slice comparison: `[u8]` indexing cannot hit a char-boundary
        // panic on multibyte input (M-8), and it keeps the offsets identical
        // to the old str-based slice. A shorter `s` can never equal `name`,
        // so `sb.len() < nb.len()` skipping matches the old min() semantics.
        let (sb, nb) = (s.as_bytes(), name.as_bytes());
        if sb.len() < nb.len() || !sb[..nb.len()].eq_ignore_ascii_case(nb) {
            continue;
        }
        // The first name.len() bytes just matched an ASCII literal, so byte
        // name.len() is a char boundary and this str slice cannot panic.
        let mut p = &s[name.len()..];
        if p.starts_with(':') {
            p = &p[1..];
            if p.is_empty() || !p.as_bytes()[0].is_ascii_digit() {
                return false;
            }
            p = p.trim_start_matches(|c: char| c.is_ascii_digit());
        }
        if p.starts_with('/') {
            return true;
        }
        return p.is_empty();
    }
    false
}

/// `^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(:\d+)?(/.*)?$`
fn looks_like_domain(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    let mut dots = 0;
    loop {
        let start = i;
        while i < b.len() && (b[i].is_ascii_alphanumeric() || b[i] == b'-') {
            i += 1;
        }
        if i == start {
            return false;
        }
        if i < b.len() && b[i] == b'.' {
            i += 1;
            dots += 1;
            continue;
        }
        break;
    }
    if dots == 0 {
        return false;
    }
    if i < b.len() && b[i] == b':' {
        i += 1;
        if i >= b.len() || !b[i].is_ascii_digit() {
            return false;
        }
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
    }
    i >= b.len() || b[i] == b'/'
}

/// Port of `rb_url_classify` / Android `UrlIntelligence.classify`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Input {
    /// `Web(url, upgraded_to_https)`
    Web(String, bool),
    /// `Search(query, url)`
    Search(String, String),
    /// blank input stays blank
    Blank,
}

pub fn classify(input: &str, engine_id: &str) -> Input {
    let raw = input.trim_matches(is_ws).to_string();
    if raw.is_empty() {
        return Input::Blank;
    }
    // file:// loads as-is.
    if ci_starts(&raw, "file://") {
        return Input::Web(raw, false);
    }
    // Interior whitespace → search.
    if has_ws(&raw) {
        let url = crate::search::search_url(engine_id, &raw);
        return Input::Search(raw, url);
    }
    // Address literals / localhost → http, never https.
    if looks_like_ipv4(&raw) {
        return Input::Web(format!("http://{raw}"), false);
    }
    if raw.contains(':') && looks_like_ipv6(&raw) {
        return Input::Web(format!("http://[{raw}]"), false);
    }
    if looks_like_localhost(&raw) {
        return Input::Web(format!("http://{raw}"), false);
    }
    if scheme_prefix_len(&raw) > 0 {
        if ci_starts(&raw, "http://") || ci_starts(&raw, "https://") {
            // Explicitly typed scheme is explicit user intent.
            return Input::Web(raw, false);
        }
        // about:/data:/javascript: typed into the omnibox are searched, not run.
        let url = crate::search::search_url(engine_id, &raw);
        return Input::Search(raw, url);
    }
    if looks_like_domain(&raw) {
        let https = format!("https://{raw}");
        let upgraded = crate::https::upgrade_to_https(&https).unwrap_or(https.clone());
        let flag = is_https(&upgraded);
        return Input::Web(upgraded, flag);
    }
    let url = crate::search::search_url(engine_id, &raw);
    Input::Search(raw, url)
}

/// The single omnibox decider: returns the URL to load.
pub fn decide(input: &str, engine_id: &str) -> String {
    match classify(input, engine_id) {
        Input::Web(url, _) | Input::Search(_, url) => url,
        Input::Blank => String::new(),
    }
}

pub fn is_http(url: &str) -> bool {
    // Byte-slice comparison: a multibyte URL whose 7th byte falls inside a
    // multi-byte char must return false, not panic (M-8).
    let b = url.as_bytes();
    b.len() >= 7 && b[..7].eq_ignore_ascii_case(b"http://")
}

pub fn is_https(url: &str) -> bool {
    let b = url.as_bytes();
    b.len() >= 8 && b[..8].eq_ignore_ascii_case(b"https://")
}

/// Host of a `scheme://...` URL (userinfo dropped, brackets kept for IPv6,
/// port dropped). None when there is no authority.
pub fn host_of(url: &str) -> Option<String> {
    let sep = url.find("://")?;
    let auth = &url[sep + 3..];
    let end = auth.find(['/', '?', '#']).unwrap_or(auth.len());
    let mut host = &auth[..end];
    if host.is_empty() {
        return None;
    }
    if let Some(at) = host.rfind('@') {
        host = &host[at + 1..];
    }
    if host.is_empty() {
        return None;
    }
    // Strip port unless IPv6-bracketed.
    if !host.starts_with('[') {
        if let Some(colon) = host.find(':') {
            host = &host[..colon];
        }
    } else if let Some(close) = host.find(']') {
        host = &host[..=close];
    }
    Some(host.to_string())
}

/// Display form for the omnibox: hide scheme (keep it when non-http(s)),
/// hide userinfo, keep host+path+query.
pub fn display_url(url: &str) -> String {
    // The str slices below are safe: is_https/is_http just matched an ASCII
    // literal prefix, so bytes 7/8 are guaranteed char boundaries (M-8).
    if is_https(url) {
        return url[8..].to_string();
    }
    if is_http(url) {
        return url[7..].to_string();
    }
    url.to_string()
}

/// Minimal percent-encoder over raw bytes: every byte outside the RFC 3986
/// unreserved set (ALPHA / DIGIT / `-` / `.` / `_` / `~`) is emitted as
/// `%XX` with uppercase hex. That covers `& = ? # + space " ' < > %` and all
/// non-ASCII bytes, so a page URL encoded with this can never contribute
/// query parameters of its own to a wrapper URL.
fn pct_encode(s: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(b as char);
            }
            _ => {
                out.push('%');
                out.push(HEX[(b >> 4) as usize] as char);
                out.push(HEX[(b & 0xf) as usize] as char);
            }
        }
    }
    out
}

/// Builds the Google Translate wrapper URL for a page — the desktop port of
/// Android `PageTranslate.urlFor`, keeping the wrapper shape the shells have
/// always used (`https://translate.google.com/translate?sl=...&tl=...&u=...`).
///
/// Every component is percent-encoded as a whole query-parameter value: the
/// page's own query string stays inside `u=` (a page containing `&tl=xx` or
/// `&u=` can no longer rewrite the wrapper's parameters), and a hostile
/// language code cannot inject parameters either. An empty `raw_url` keeps
/// the legacy shape with an empty `u=` (the shells guard that case before
/// calling).
pub fn translate_url(raw_url: &str, source_lang: &str, target_lang: &str) -> String {
    format!(
        "https://translate.google.com/translate?sl={}&tl={}&u={}",
        pct_encode(source_lang),
        pct_encode(target_lang),
        pct_encode(raw_url),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn web(input: &str) -> String {
        decide(input, "duckduckgo")
    }

    #[test]
    fn searches_and_urls() {
        assert_eq!(web("how to boil water"), "https://duckduckgo.com/?q=how%20to%20boil%20water");
        // classify keeps the bare-authority URL exactly as the C core's
        // rb_url_classify does (rb_url_normalize's slash-insertion is a
        // separate helper the engines don't need).
        assert_eq!(web("example.com"), "https://example.com");
        assert_eq!(web("example.com/path?q=1"), "https://example.com/path?q=1");
        // The domain regex only admits path slashes after the host — a bare
        // '?' or '#' makes it search text, exactly like the C/Kotlin core.
        assert_eq!(web("example.com?q=1"), "https://duckduckgo.com/?q=example.com%3Fq%3D1");
        assert_eq!(web("example.com#frag"), "https://duckduckgo.com/?q=example.com%23frag");
        assert_eq!(web("http://plain.example"), "http://plain.example");
        assert_eq!(web("https://sec.example/x"), "https://sec.example/x");
        assert_eq!(web("192.168.1.10"), "http://192.168.1.10");
        // With a path appended, the anchored IPv4 regex no longer matches and
        // the dotted name lands in the domain branch → https, exactly like C.
        assert_eq!(web("192.168.1.10:8080/x"), "https://192.168.1.10:8080/x");
        assert_eq!(web("::1"), "http://[::1]");
        assert_eq!(web("localhost"), "http://localhost");
        assert_eq!(web("localhost:9000/x"), "http://localhost:9000/x");
        assert_eq!(web("file:///home/x.html"), "file:///home/x.html");
        // about:/javascript: typed are searched, not executed.
        assert_eq!(web("about:blank"), "https://duckduckgo.com/?q=about%3Ablank");
        // parens are percent-encoded like every other reserved byte
        assert_eq!(web("javascript:alert(1)"), "https://duckduckgo.com/?q=javascript%3Aalert%281%29");
        // single word = search text
        assert_eq!(web("rumah"), "https://duckduckgo.com/?q=rumah");
        // "1.5" is a two-label domain and "12:30" matches the loose IPv6
        // heuristic — both classify as Web, exactly like the C/Kotlin core
        // (the "stays search" comments in the C file are about
        // rb_url_is_probably_url, a different helper).
        assert!(matches!(classify("1.5", "duckduckgo"), Input::Web(_, _)));
        assert!(matches!(classify("12:30", "duckduckgo"), Input::Web(_, _)));
        // 999.999.999.999 passes the loose ip check like the Kotlin one
        assert!(matches!(classify("999.999.999.999", "duckduckgo"), Input::Web(_, _)));
    }

    #[test]
    fn is_probably_url_contract() {
        assert!(is_probably_url("example.com"));
        assert!(is_probably_url("https://a.com"));
        assert!(!is_probably_url("how to x"));
        assert!(!is_probably_url("satu dua"));
    }

    #[test]
    fn host_extraction() {
        assert_eq!(host_of("https://user:pass@Example.com:8443/x?q"), Some("Example.com".into()));
        assert_eq!(host_of("http://[::1]:99/x"), Some("[::1]".into()));
        assert_eq!(host_of("about:blank"), None);
    }

    // --- translate_url (M-9: the desktop PageTranslate port) ----------------

    #[test]
    fn translate_url_matches_old_wrapper_shape() {
        // The shells used to build
        // format!("https://translate.google.com/translate?sl=auto&tl={target}&u={url}")
        // — same host, same path, same parameter order; only now every
        // component is percent-encoded.
        let out = translate_url("https://example.com/page", "auto", "id");
        assert_eq!(
            out,
            format!(
                "https://translate.google.com/translate?sl=auto&tl={}&u={}",
                pct_encode("id"),
                pct_encode("https://example.com/page")
            )
        );
        assert_eq!(
            out,
            "https://translate.google.com/translate?sl=auto&tl=id&u=https%3A%2F%2Fexample.com%2Fpage"
        );
    }

    #[test]
    fn translate_url_query_stays_one_value() {
        // The page's own ?/&/# must stay inside u= — no &tl= / &u= parameter
        // injection from the wrapped URL.
        assert_eq!(
            translate_url("https://x.test/?a=1&b=2#f", "auto", "en"),
            "https://translate.google.com/translate?sl=auto&tl=en&u=https%3A%2F%2Fx.test%2F%3Fa%3D1%26b%3D2%23f"
        );
    }

    #[test]
    fn translate_url_space_quotes_and_unicode() {
        // space, quotes, ?, =, + and a literal %20 are all percent-encoded.
        assert_eq!(
            translate_url("https://x.test/a b\"c\"?d=e+f%20g", "auto", "ja"),
            "https://translate.google.com/translate?sl=auto&tl=ja&u=https%3A%2F%2Fx.test%2Fa%20b%22c%22%3Fd%3De%2Bf%2520g"
        );
        // All non-ASCII bytes are encoded as UTF-8 with uppercase hex.
        assert_eq!(pct_encode("日"), "%E6%97%A5");
        assert_eq!(pct_encode("İ"), "%C4%B0");
        assert_eq!(pct_encode("🎉"), "%F0%9F%8E%89");
        assert_eq!(
            translate_url("https://例え.jp/テスト", "auto", "ja"),
            "https://translate.google.com/translate?sl=auto&tl=ja&u=https%3A%2F%2F%E4%BE%8B%E3%81%88.jp%2F%E3%83%86%E3%82%B9%E3%83%88"
        );
    }

    #[test]
    fn translate_url_empty_input_keeps_empty_u() {
        // Legacy format! behaviour for empty input: wrapper with an empty u=.
        assert_eq!(
            translate_url("", "auto", "id"),
            "https://translate.google.com/translate?sl=auto&tl=id&u="
        );
    }

    #[test]
    fn pct_encode_unreserved_passthrough() {
        // RFC 3986 unreserved set passes through byte-identical.
        assert_eq!(pct_encode("AZaz09-._~"), "AZaz09-._~");
        // Every byte the audit named is encoded.
        assert_eq!(pct_encode("&=?# +\"'<>%"), "%26%3D%3F%23%20%2B%22%27%3C%3E%25");
        // Language codes go through the same encoder.
        assert_eq!(
            translate_url("https://x.test", "auto", "zh-CN&evil=1"),
            "https://translate.google.com/translate?sl=auto&tl=zh-CN%26evil%3D1&u=https%3A%2F%2Fx.test"
        );
    }

    // --- multibyte slicing regressions (M-8) --------------------------------

    #[test]
    fn classify_survives_multibyte_omnibox_input() {
        // The first three panicked with `byte index N is not a char boundary`
        // before the byte-slice fixes: byte 7 lands inside a 3-byte CJK char
        // (ci_starts), byte 9 inside a CJK char / emoji (looks_like_localhost).
        assert!(matches!(classify("abcde日://", "duckduckgo"), Input::Search(_, _)));
        assert!(matches!(classify("localhos日", "duckduckgo"), Input::Search(_, _)));
        assert!(matches!(classify("localhos🎉", "duckduckgo"), Input::Search(_, _)));
        assert!(matches!(classify("İstanbul", "duckduckgo"), Input::Search(_, _)));
        // Non-ASCII localhost-ish input stays out of the localhost branch.
        assert!(!is_probably_url("localhost日"));
        assert!(!is_probably_url("🎉🎉🎉"));
    }

    #[test]
    fn scheme_predicates_survive_multibyte_urls() {
        // "https" followed by a 3-byte CJK char: its 7th byte is a UTF-8
        // continuation byte, so the old str slices panicked. Same for the
        // Turkish İ run at byte 7 of "İİİİhttps".
        assert!(!is_http("https日"));
        assert!(!is_https("İİİİhttps"));
        assert!(!is_http("http日://x"));
        // display_url used to panic through is_http on the first input.
        assert_eq!(display_url("https日://x"), "https日://x");
        // Positive paths still match and slice on real boundaries.
        assert!(is_https("https://日.example"));
        assert_eq!(display_url("https://日.example/テスト"), "日.example/テスト");
        assert_eq!(display_url("http://例え.jp"), "例え.jp");
    }
}
