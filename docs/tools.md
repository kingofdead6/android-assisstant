# Tools

A tool is the only way the model reaches the device.

## The contract

```kotlin
interface AssistantTool {
    val name: String                              // lower_snake_case, what the model emits
    val description: String                       // one line, written for the model
    val parameters: ToolParameters                // the only inputs that can reach execute()
    val riskLevel: RiskLevel                      // LOW / MEDIUM / HIGH
    val requiredPermissions: Set<PermissionKey>   // checked before execute()
    val worksOffline: Boolean
    val examples: List<String>

    suspend fun execute(arguments: ToolArguments): ToolResult

    fun describeAction(arguments: ToolArguments): String   // "send Mom a message saying…"
}
```

Three rules implementations must hold:

- **Independent** — no tool calls another tool.
- **Total** — every failure path returns a `ToolResult`. Never throw for an
  expected condition. (A tool that throws anyway is caught and reported; it does
  not take the session down.)
- **Honest** — only return `Success` when the action really happened. A tool
  that reports a WhatsApp message as sent when it was merely composed is the
  single worst bug this codebase can have.

## Results

```kotlin
sealed interface ToolResult {
    data class Success(message: String, data: Map<String, Any?>, spoken: Boolean)
    data class Failure(message: String, recoverable: Boolean, cause: Throwable?)
    data class RequiresPermission(permission: PermissionKey, message: String)
    data class RequiresConfirmation(confirmationMessage: String, retryArguments: ToolArguments)
    data class NeedsClarification(question: String, options: List<ClarificationOption>)
}
```

`message` is written as **speech**, because with the default settings it is what
gets spoken verbatim. "You're at 74 percent", not "battery_level=74".

`data` feeds two things: conversational referents for the next turn, and the UI.
Only an allow-list of keys is carried forward — `app_label`, `contact_name`,
`track`, and a few others. Notification bodies and phone numbers are never
carried, even when a tool returns them.

`spoken = false` is for actions whose result is obvious on screen. John does not
announce "volume is at 60 percent" when the volume HUD already said so.

## Arguments are validated, not trusted

`ToolParameters.validate()` is the gate between model output and execution:

- **Unknown keys are discarded.** Small models add commentary keys; that is not
  an error, but they never reach the tool.
- **Required parameters must be present and non-blank.**
- **Values are coerced to the declared type**, or rejected. `"7"` becomes `7`;
  `7.5` is rejected for an integer parameter.
- **Enums and numeric bounds are enforced**, case-insensitively for enums.

By the time a tool receives a `ToolArguments`, every accessor is safe. `ToolParametersTest`
covers each rule.

## Risk and confirmation

| Level | Meaning | Examples |
|---|---|---|
| `LOW` | Reversible, no side effect anyone else sees | open app, battery, pause |
| `MEDIUM` | Visible to others or hard to undo | send message, create event, call |
| `HIGH` | Money, data loss, anything John must never do unprompted | payment |

`ConfirmationPolicy` decides what confirms. It is configurable, with one rule
that is not: **`HIGH` always confirms**, whatever the settings say and whatever
a `never_confirm` entry claims. Asserted in `ConfirmationPolicyTest` and
`AssistantOrchestratorTest`.

When a tool confirms, `describeAction()` supplies the wording. Override it for
anything with a side effect — *"Do you want me to run send message?"* is not a
question a person can answer.

## Clarification

`NeedsClarification` is how John avoids guessing. "Mom has two numbers. Which
one?" comes back with `ClarificationOption`s, each carrying the arguments to
re-run with. The user's answer is matched by `ChoiceMatcher`, which handles
ordinals ("the second"), partial labels ("the mobile one") and numerals ("number
three") — and **returns nothing when the answer fits two options**, so John asks
again rather than calling the wrong person.

Note that picking from a list is not consent to the underlying action: a
`MEDIUM`-risk tool still confirms afterwards.

## The tools

### Apps and the web
| Tool | Notes |
|---|---|
| `open_app` | Resolves against installed apps; asks when several match |
| `list_apps` | Also lets John say what *is* installed when it cannot find something |
| `search_google` | `ACTION_WEB_SEARCH`; no scraping |
| `open_url` | Adds `https://` to a bare domain |
| `search_maps` | `geo:` scheme, falls back to a maps URL |

### Media
`play_media` · `pause_media` · `resume_media` · `next_track` · `previous_track` ·
`get_now_playing` · `increase_volume` · `decrease_volume` · `set_volume`

`play_media` consults memory: with "my music app is Spotify" remembered, a bare
"play some music" uses Spotify. `get_now_playing` needs notification access.

### System
`get_battery` · `get_time` · `get_date` · `get_device_state` · `open_settings` ·
`toggle_flashlight` · `open_camera`

`open_settings` exists because Wi-Fi and Bluetooth cannot be toggled by a
third-party app. It opens the system panel instead of pretending.

### People
| Tool | Risk | Notes |
|---|---|---|
| `make_phone_call` | MEDIUM | Disambiguates contacts and numbers |
| `find_contact` | LOW | Look up without calling |
| `send_message` | MEDIUM | SMS sends; other channels compose |
| `read_notifications` | LOW | Summary by default, contents on request |

### Time
`set_alarm` · `set_timer` · `create_reminder` · `read_calendar` ·
`create_calendar_event` (MEDIUM)

### Memory
`remember_fact` · `recall_memory` · `forget_memory` (MEDIUM)

Nothing reaches long-term memory except through `remember_fact`. There is no
inference path that writes memories from ordinary conversation — which is what
makes the memory screen's list complete.

### Connected accounts *(optional, online)*
`github_repositories` · `github_notifications`

Read-only scopes. John cannot open issues, push or comment.

### Screen automation *(optional, requires accessibility)*
`read_screen` · `tap_on_screen` (MEDIUM) · `navigate_screen`

Last resort, for apps with no other way in. Every one can fail, and says so.

## Adding a tool

1. Implement `AssistantTool` in `app/tools/<domain>/`.
2. Write a `description` the model can choose from — one clear line saying what
   it does *and when to pick it*.
3. Declare every input in `parameters`. Anything undeclared cannot reach you.
4. Set `riskLevel` honestly, and override `describeAction` if it is not `LOW`.
5. Declare `requiredPermissions` and `worksOffline`.
6. Add it to the constructor list in `di/ToolModule.kt`.

Step 6 is the capability boundary. A tool that is not in that list does not
exist as far as the model is concerned — `ToolRegistry.resolve` will not return
it, so a hallucinated call cannot execute. That is deliberate: adding a
capability should be a reviewable line of code.

`ProductionToolRegistryTest` (instrumentation) checks that every registered tool
honours the contract on-device.
