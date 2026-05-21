# Terminal Link to Command — JetBrains Plugin

Makes `[pick] CW-123` text in the terminal clickable.
Clicking it runs `new CW-123` in the active terminal tab.

## How it works

1. Your `subtasks` command outputs lines ending with `[pick] CW-36237`
2. This plugin scans every terminal line for that pattern
3. `[pick]` becomes a blue hyperlink
4. Click it → `new CW-36237` executes in the terminal automatically

## Build & Install

**Requirements:** JDK 17+, internet connection (first build downloads Gradle + WebStorm)

```bash
cd terminal-link2command
./gradlew buildPlugin
```

This produces: `build/distributions/terminal-link2command-1.0.0.zip`

**Install in WebStorm:**
Settings → Plugins → ⚙️ → Install Plugin from Disk → select the .zip

## Customising the command

Edit `CommandFilter.kt` — change `"new $ticketKey"` to any command you want.
You can also add more patterns (e.g. `[open]` → open in browser, `[pr]` → create-pr).
