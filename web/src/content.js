// Page content in one place, so copy edits never mean hunting through JSX.

export const pipeline = [
  { n: '01', title: 'Wake word', body: 'Optional, off by default. Continuous recognition, so it costs battery — and says so.' },
  { n: '02', title: 'Speech to text', body: 'Platform recogniser, asked to stay on-device where it can.' },
  { n: '03', title: 'Context', body: 'Recent turns, plus anything you told John to remember.' },
  { n: '04', title: 'Model', body: 'Returns one tool name and its arguments. Nothing else.' },
  { n: '05', title: 'Validate', body: "Arguments are checked against the tool's schema. Untrusted until they pass.", gate: true },
  { n: '06', title: 'Permission', body: 'Does Android actually allow this, right now?', gate: true },
  { n: '07', title: 'Confirm', body: 'Anything visible to other people waits for a yes.', gate: true },
]

export const risks = [
  { level: 'Low', tone: 'low', title: 'Just do it', body: 'Reversible, with no side effect anyone else sees. Opening an app, reading the battery, checking the time.' },
  { level: 'Medium', tone: 'med', title: 'Ask first', body: 'Visible to other people, or awkward to undo. Sending a message, creating a calendar event, placing a call.' },
  { level: 'High', tone: 'high', title: 'Never unprompted', body: 'Money, or data loss. Reserved for things John must never take on its own initiative.' },
]

// Mirrors the tool names registered in
// app/src/main/java/com/john/assistant/di/ToolModule.kt — keep in step.
export const tools = [
  'open_app', 'list_apps', 'search_google', 'open_url', 'search_maps',
  'play_media', 'pause_media', 'resume_media', 'next_track', 'previous_track',
  'get_now_playing', 'set_volume', 'increase_volume', 'decrease_volume',
  'make_phone_call', 'find_contact', 'send_message', 'read_notifications',
  'get_battery', 'get_time', 'get_date', 'get_device_state',
  'toggle_flashlight', 'open_camera', 'open_settings', 'set_alarm',
  'set_timer', 'create_reminder', 'read_calendar', 'create_calendar_event',
  'remember_fact', 'recall_memory', 'forget_memory', 'read_screen',
  'tap_on_screen', 'navigate_screen', 'github_repositories',
  'github_notifications',
]

export const engines = [
  {
    name: 'On-device',
    active: true,
    body: 'The LiteRT-LM runtime ships inside the APK, with its own native libraries — nothing to compile. You supply the weights.',
    spec: [
      ['Model', 'Gemma 3 1B Instruct'],
      ['Size', '584 MB'],
      ['RAM', '~1.4 GB'],
      ['Network', 'None, ever'],
    ],
  },
  {
    name: 'Hugging Face API',
    active: false,
    body: 'For phones without the memory for a local model. A token is required — the Inference API refuses anonymous requests, so John refuses to send them.',
    spec: [
      ['Model', 'Any text-generation ID'],
      ['Size', 'No download'],
      ['Token', 'Android Keystore'],
      ['Network', 'Prompts leave the phone'],
    ],
  },
]

export const honesty = [
  {
    title: 'It cannot send that WhatsApp for you',
    body: 'No third-party app can. John opens the conversation with your message typed and says “it’s ready — tap send,” rather than claiming a send it never made.',
  },
  {
    title: 'It cannot switch on Wi-Fi or Bluetooth',
    body: 'Blocked for third-party apps since Android 10 and 13. “Turn on Bluetooth” opens the system toggle instead.',
  },
  {
    title: 'It cannot make itself your default assistant',
    body: "It can appear in Android's picker. The rest is your choice and your manufacturer's policy.",
  },
  {
    title: 'Speech timeouts are hints, not guarantees',
    body: 'John asks for a two-second pause before your sentence is cut off. Android permits a recogniser to ignore that, and some OEM ones do.',
  },
  {
    title: 'The wake word costs battery',
    body: 'It uses continuous speech recognition, not a dedicated keyword spotter. Off by default, and when on, it runs behind a permanent visible notification.',
  },
  {
    title: 'Voice may not stay on the phone',
    body: 'John asks the recogniser to stay offline, but whether it obeys depends on which one you have installed. The privacy screen reports what can actually be determined.',
  },
]

export const REPO = 'https://github.com/kingofdead6/android-assisstant'
