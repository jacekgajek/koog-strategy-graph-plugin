# Publishing

Two options, ordered by speed.

## Option A — Sideload into your main IDE (no marketplace approval)

The fastest path; great for personal use or sharing the ZIP with a few people.

```bash
./gradlew buildPlugin
# → build/distributions/koog-strategy-graph-plugin-0.1.0.zip
```

In your main IDE:

1. *Settings → Plugins → ⚙ icon (top of the Marketplace tab) → **Install Plugin from Disk***.
2. Pick the ZIP from `build/distributions/`.
3. Restart the IDE when prompted.

That's it — no account, no review, no waiting. Updates are manual: rebuild,
reinstall, restart.

## Option B — Publish to the JetBrains Marketplace

Reach: anyone running IntelliJ-family IDEs can install it from
*Settings → Plugins → Marketplace*. First-time approval takes 1–2 business
days; subsequent updates are usually auto-approved within minutes.

### One-time setup

1. **Pre-publish checklist** — the Marketplace gate will reject the ZIP if any
   of these are wrong:
   - `plugin.xml`'s `<vendor email>` is reachable (current value is a
     placeholder — fix before submitting).
   - `<id>` is globally unique. The current `io.github.jacekgajek.koog-strategy-graph`
     is fine.
   - The plugin has an icon. Drop two SVGs into
     `src/main/resources/META-INF/`:
     - `pluginIcon.svg` (light theme)
     - `pluginIcon_dark.svg` (dark theme)
     40×40 design grid, mostly monochrome — see the
     [icon guidelines](https://plugins.jetbrains.com/docs/marketplace/plugin-icon-file.html).
   - Run `./gradlew verifyPlugin` and fix anything it flags. (Optional but
     this is the same check the Marketplace runs.)

2. **Account + token**
   - Create an account at https://plugins.jetbrains.com (if you don't have one).
   - Go to https://plugins.jetbrains.com/author/me/tokens, click **Generate**.
   - Stash it somewhere safe — you won't see it again.

3. **First upload** — has to be manual the very first time so the listing,
   description, and tags can be reviewed:
   - Build the ZIP: `./gradlew buildPlugin`.
   - Go to https://plugins.jetbrains.com/plugin/add, upload the ZIP, fill in
     the public name / description / tags / screenshots. Submit for review.
   - After approval the plugin gets a numeric ID; the listing URL looks like
     `plugins.jetbrains.com/plugin/12345-koog-strategy-graph`.

### Subsequent releases — one command

Once the listing exists, every new version goes through Gradle. Bump
`pluginVersion` in `gradle.properties`, then:

```bash
export INTELLIJ_PLATFORM_PUBLISH_TOKEN=...your-token...
./gradlew publishPlugin
```

This runs `buildPlugin` and posts the ZIP to the Marketplace via REST API.
The Marketplace auto-verifies and (usually) auto-approves bug-fix updates;
material changes may go to manual review again.

Useful flags:

| Flag                              | Effect                                              |
|-----------------------------------|-----------------------------------------------------|
| `-Pchannel=eap`                   | Publish to the `eap` channel (opt-in users only).   |
| `-Pchannel=beta`                  | Publish to the `beta` channel.                      |
| `-PintellijPlatform.publish.token=...` | Pass the token as a Gradle property instead of env. |

### Versioning gotcha

The Marketplace rejects uploads where `pluginVersion` is `≤` the latest
published version. Bump it before every publish.

### Verifying before submission

```bash
./gradlew verifyPlugin
```

Runs the same plugin verifier the Marketplace uses, against the IDE versions
in your compatibility range (`sinceBuild = 242`, no upper bound). Catches:

- API uses that don't exist on older IDE builds.
- Internal-API uses (`@ApiStatus.Internal`).
- Missing extension points.

Fix everything `verifyPlugin` complains about before `publishPlugin`.

## Option C — Private repository (team/internal)

If you want a marketplace-like install flow but don't want to publish
publicly, host the ZIP and an `updatePlugins.xml` on any HTTPS server (S3,
GitHub Pages, internal Artifactory). Point your team's IDEs at
*Settings → Plugins → ⚙ → Manage Plugin Repositories → +* with that URL.

Minimal `updatePlugins.xml`:

```xml
<plugins>
    <plugin
        id="io.github.jacekgajek.koog-strategy-graph"
        url="https://example.com/koog-strategy-graph-plugin-0.1.0.zip"
        version="0.1.0">
        <idea-version since-build="242"/>
    </plugin>
</plugins>
```

Update both files (XML and ZIP) on each release.
