# AI Agents — Autonomous Browsing (Room Agent)

Room Browser ships **Room Agent**, an autonomous browsing assistant inspired
by the agent-mode experience of chat.z.ai and the provider flexibility of
opencode: you type a task, the agent drives the real browser — navigating,
reading pages, clicking, filling forms and managing tabs — while you watch
it happen behind the chat panel.

```
┌───────────────────────────────┐
│  (the live web page)          │
│  … the agent clicks/types …   │
├───────────────────────────────┤
│ ✨ Room Agent    [z.ai·glm-4.6]│
│ ┌───────────────────────────┐ │
│ │ you: open example.com and │ │
│ │      summarize it         │ │
│ │ ⚙ Open https://example… ✓ │ │
│ │ 📖 Read current page … ✓  │ │
│ │ agent: The page says…     │ │
│ └───────────────────────────┘ │
│ [Include page] [Ask…    (➤)] │
└───────────────────────────────┘
```

## Where to find it

| Entry point | Action |
|---|---|
| Menu (⚙) → **AI Agents** | Expands the agent panel (always available) |
| Floating pill (bottom-right of the browser) | Opt-in — see **Show AI Agent button** below; once shown, tap to expand the panel, and while a task runs it streams live progress ("clicking [12] Sign in…") |
| Menu → **AI Agent settings (providers & models)** | Opens the AI settings **activity** (its own window) |
| Menu → **AI Agent chats** | Opens the per-profile chat history **activity** |
| Panel header | Model picker (provider · model), new chat, history, settings |

### Show / hide the floating agent button

The floating AI Agent button is **hidden by default** so the browser stays
minimal. Turn it on via either:

* **Browser Settings → AI Agent → Show AI Agent button**, or
* **AI Agent settings → Agent behavior → Show AI Agent button**.

While a task is actively running the pill always appears (live progress
stays visible) and hides again when the turn finishes. The agent itself
remains reachable at any time from the page menu (⚙) → *AI Agents*.

## Providers & models (manual input, like opencode)

Any **OpenAI-compatible** endpoint works. In *AI Agent settings → Add
provider*:

1. **Pick a preset or type a custom base URL** — Z.ai, OpenAI, OpenRouter,
   Groq, DeepSeek, Mistral, Together, Ollama (`http://localhost:11434/v1`),
   LM Studio (`http://localhost:1234/v1`) or any custom gateway.
2. **Paste your API key** (optional for local servers). Keys are encrypted
   with an **AndroidKeyStore AES-256-GCM** key and stored only on this
   device.
3. **Fetch models** — the model list is retrieved live from the provider's
   `GET {baseUrl}/models` endpoint and shown as selectable chips. A
   **Search models** field above the chips filters long lists live (type
   e.g. `glm` or `mini`), shows "N of M models" and can be cleared with one
   tap. If a provider doesn't expose `/models`, type the model id manually
   (always available).
4. **Save** — the provider becomes selectable in the panel's model picker.

Wire protocol used: `POST {baseUrl}/chat/completions` with
`tools` (function calling) and `stream: true` (SSE), including
`reasoning_content` passthrough for reasoning models. Non-streaming JSON
responses are accepted as a fallback automatically.

## What the agent can do (tool catalogue)

| Tool | Effect |
|---|---|
| `navigate(url)` | Load a URL in the current tab (waits for page finish) |
| `search_web(query)` | Runs the profile's search engine |
| `read_page()` | Extracts URL, title, visible text and every interactive element with a `[ref]` number |
| `click(ref)` | Clicks element `[ref]` (scrolls it into view first) |
| `fill_input(ref, text)` | Types into inputs/textareas — uses the native value setter so React/Vue forms register it |
| `press_enter(ref?)` | Submits the focused / given form |
| `scroll(direction, amount?)` | Scrolls the page |
| `go_back()` | History back |
| `open_new_tab(url?)`, `list_tabs()`, `switch_tab(index)`, `close_tab()` | Tab management |
| `auto_like()` | Likes/upvotes the posts **currently visible** on the page (X, Facebook, Reddit, Tumblr, LinkedIn…) — up to 20 per call; scroll then repeat to continue |
| `auto_repost()` | Reposts/retweets/reblogs/shares the visible posts — up to 15 per call |
| `auto_reply(text)` | Types the text into the visible reply box and submits it |
| `auto_post(text)` | Opens the composer, types a new post/status/tweet and submits it |
| `wait(ms)` | Waits for post-submit animations / infinite-scroll loading before reading again |

Element interaction uses the numbered-reference model (every visible
interactive element is tagged `data-agent-ref` by injected JS — the same
family of techniques used by WebVoyager-style browser agents, adapted to
Android WebView).

### Social automation ("auto selesaikan task")

The four `auto_*` tools are heuristics that work across social sites by
matching visible button labels (EN + ID: *like/suka, repost/bagikan ulang,
reply/balas, post/tweet…*) and typing into the visible composer with the
React/Vue-safe native value setter. Anything they cannot solve directly,
the model still solves with the generic tools (`read_page` → `click` →
`fill_input` → `press_enter`) — so phrased tasks like *"balas semua DM
yang bilang halo"* or *"like 50 post tentang AI"* get decomposed into
scroll → `auto_like` → verify loops. Automation tools are part of the
**Confirm actions** gate, so you can require an Allow/Deny tap before the
agent likes/posts anything.

### Background execution

Agent turns keep running when you leave the app or turn the screen off:
while a turn is active, `AgentKeepAliveService` (a `dataSync` foreground
service in the `:browser` process) holds a partial wake lock and shows an
ongoing progress notification (current step + a **Stop** action). The
service starts when you hit Send and stops itself when the turn finishes.
Note the honest limits: swiping the app away from Recents kills the
process (standard Android behaviour), and the system caps `dataSync`
services at ~6 hours per day on Android 14+ — far beyond any realistic
agent task.

## File attachments in the composer

Next to the "Include page" toggle there is an attach (📎) button: pick any
files from the device (SAF picker, multi-select). Text-like files (txt, md,
json, csv, xml, yaml, html, source files… up to 256 KB each) are read and
inlined into the turn's prompt (capped at 20 000 chars per file and ~60 000
chars per turn); binary files contribute name/size metadata only. Attached
chips can be removed before sending; the user bubble and the persisted chat
row list the file names. Content-URIs are not persisted across process
death — attachments live until sent.

## Copy any chat message (re-use prompts)

Every bubble in the conversation carries a small copy icon (⧉) underneath:
tap it and the bubble's exact text lands on the system clipboard, with the
icon flipping to a check plus a tiny "Copied" label as feedback (~1.8 s).
This works for **your own previously sent prompts** — copy one, paste it back
into the composer and re-process it with the agent (optionally on a different
provider/model) — and for **finished assistant answers** (summaries, extracted
data, generated text) you want to reuse elsewhere. Icons are deliberately not
shown on half-streamed answers, so you can never copy a truncated reply.
Long-press selection is not needed; the affordance is one visible, tappable
icon per message.

## Agent settings (own activity)

AI settings, the provider editor and the chat history each run as their own
**activity** — a real window with its own back-stack entry, keyboard
handling and edge-to-edge insets (nothing ever overlaps the system
Back / Home / Recents buttons). They run in the default process (no WebView)
and share state with the live agent in the `:browser` process through the
Room database (multi-instance invalidation) — every provider or setting
change is picked up by the running browser instantly.

* **Add / edit provider** — its own activity (`AgentProviderEditorActivity`):
  presets, manual base URL + API key, live model discovery from `/models`.
* **Show AI Agent button** — visibility of the floating button (hidden by
  default; also switchable from Browser Settings).
* **Confirm actions** — require Allow/Deny approval before every click,
  type or submit (off by default = fully autonomous within the step budget).
* **Include current page by default** — attaches a page snapshot to the
  first message of each turn.
* **Temperature** (0–1) and **max steps per turn** (5–50, default 25) —
  the step budget bounds cost and runaway loops; when exhausted the agent
  is asked once more, without tools, to produce a final answer.
* **System prompt override** — replace the built-in browsing-agent prompt.
* **Data & privacy** — delete all agent chats (every profile; providers
  are kept).

## Privacy model (honest)

* Agent requests go **directly** from the device to the configured
  provider. Room Browser adds **no proxy, no telemetry, no analytics**.
* API keys are encrypted at rest (AndroidKeyStore) and are only ever sent
  as the `Authorization: Bearer` header **to the provider you configured**.
* Chat history is stored locally in Room, **scoped to the profile** that
  produced it (switching profiles switches agent history too).
* Page snapshots sent to the provider contain visible page text and links —
  decide for yourself whether that is acceptable for the sites you visit.
* The agent inherits the profile's ad/tracker blocking: blocked requests
  never reach the provider or the page.

## Architecture

```
core:domain (pure JVM, 100% unit-tested)
├── AgentDtos          OpenAI-compatible DTOs + ModelListParser
├── OpenCodeDtos       opencode /provider + text tool-call parsers + wire bodies
├── SseParser          line-oriented SSE parsing
├── AgentTools         tool catalogue + JSON schemas + snapshot formatter
├── AgentPrompts       built-in system prompt
└── AgentLoop          plan → act → observe → repeat (bounded by maxSteps)

app (:browser process — owns the WebView)
├── OkHttpAgentGateway     SSE streaming, tool-call delta assembly, /models
├── OpenCodeAgentGateway   `opencode serve` bridge (sessions + polling)
├── AgentGateways          picks the transport from the provider protocol
├── PageInjector           JS: element tagging, snapshot, click, fill, enter
├── AgentToolExecutor      tools against the live BrowserViewModel engine
├── BrowserAgentController chat state, sessions, approvals, persistence
├── KeyStoreCrypto         AES-256-GCM for API keys
└── agent/ui/*             AgentPanel (pill + chat)

app (default process — agent settings activities, no WebView)
├── AgentProviderStore     shared provider validation + encryption
├── AgentSettingsController settings/providers persistence via Room
└── agent/ui/*             AgentSettingsActivity, AgentProviderEditorActivity,
                          AgentSessionsActivity (own windows)
```

Room schema **v3** adds the `protocol` column to `agent_providers`
(`OPENAI` | `OPENCODE`, default `OPENAI`) — lossless additive migrations
(v1→v2 agent tables, v2→v3 protocol). Sessions are profile-scoped;
providers are app-global credentials.

## Tests

* **Domain (JVM)**: `AgentLoopTest` (loop semantics, tool failures, step
  limit, history trimming), `SseParserTest`, `AgentDtosTest`
  (wire format, model-list shapes, snapshot formatting),
  `OpenCodeParsersTest` (text tool-call extraction: fenced/bare/actions
  forms, non-tool JSON passthrough; `/provider` model flattening; wire
  bodies).
* **App (JVM)**: `AgentGatewayTest` — real MockWebServer round-trips for
  SSE streaming, tool-call delta assembly, reasoning passthrough,
  non-stream fallback, auth headers, `/models` shapes and error mapping.
  `OpenCodeAgentGatewayTest` — session creation, delta-only messaging,
  polled assistant replies with tool-call extraction, `/provider` model
  listing with endpoint fallback, timeout mapping.
* **E2E (emulator)**: `AgentSettingsE2eTest` — drives the real app UI
  across both processes: opens the agent panel from the page menu (the
  pill is hidden by default), adds a provider via the settings/editor
  activities against a local MockWebServer, fetches its model list,
  verifies the saved selection in the panel, exercises the Show/Hide AI
  Agent button toggle (off → pill hidden, on → pill shown, off → hidden)
  and opens the chat-history activity.

## Tips

* Start with cheap/fast models (e.g. `glm-4-flash`, `groq/llama-3.1-8b-instant`,
  local Ollama models) — autonomous browsing spends many turns.
* Enable **Confirm actions** the first time you let it log in anywhere.
* Ask for sources — the built-in prompt requires the agent to cite URLs.

## OpenCode backend (`opencode serve`)

Besides any OpenAI-compatible API, an AI provider can be an **OpenCode
server**: run `opencode serve` on your own PC/NAS/VPS (default port 4096)
and add it in *Add AI provider* with type **OpenCode server** (or pick the
"OpenCode (opencode serve)" preset). The model list is fetched live from
the server's `GET /provider` endpoint and shown as `providerID/modelID`
chips (e.g. `anthropic/claude-sonnet-4`).

How the bridge works (`OpenCodeAgentGateway`):

* Each agent turn creates an opencode session (`POST /session`) and posts
  only the messages not yet sent (`POST /session/{id}/message`) — the
  server-side session keeps the context, so nothing is re-sent; tool
  results travel as labelled `TOOL RESULT` messages.
* Replies are read by polling `GET /session/{id}/message`; newly appearing
  assistant text is streamed into the chat UI in deltas, so long
  generations still feel live.
* opencode has no browser tools, so the first message of a session teaches
  the model a **text tool-call protocol**: it replies with a fenced JSON
  block (`{"tool_calls":[...]}`), which the gateway parses into real tool
  calls — the same `AgentLoop` then executes them against the WebView
  (navigate/click/fill/…, auto_reply/like/repost/post). One loop, two
  transports.
* Endpoint shapes are parsed leniently and fall back across opencode
  versions (`/provider` → `/config/providers` → `/models`), so minor API
  drift degrades to a clear error instead of a crash.

Security notes:

* Keep `opencode serve` on a **private LAN or VPN (Tailscale/WireGuard)** —
  never expose port 4096 directly to the internet.
* The optional API key is stored encrypted (AndroidKeyStore) and sent as
  `Authorization: Bearer` to your server only.
* The agent executes browser actions **on the phone**; opencode's own
  terminal/file tools run on the machine hosting `opencode serve` — Room
  Browser never exposes them to the model.
