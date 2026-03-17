# CustomDiscs for SVC Addon

## Special thanks

[Navoei CustomDiscs](https://github.com/Navoei/CustomDiscs) | [henkelmax AudioPlayer](https://github.com/henkelmax/audio-player) | [sedmelluq lavaplayer](https://github.com/sedmelluq/lavaplayer)

Create music discs using local audio files or remote audio providers, then play them through Simple Voice Chat.

## Current creation flow

- `/cd create local <file> "<name>"` modifies the music disc in the player's main hand and stores a relative path to a local audio file.
- `/cd create remote "<url>" "<name>"` modifies the music disc in the player's main hand and stores the remote URL.
- `/cd download "<direct link>" <name.extension>` saves a local audio file into the configured local storage directory.
- The built-in web upload server uses the same local-disc mutation flow as `/cd create local` instead of maintaining a separate disc creation implementation.

## Configuration

```yaml
# CustomDiscs Configuration
# Join our Discord for support: https://discord.gg/eRvwvmEXWz
info:
  # Don't change this value
  version: '1.7'

global:
  # Language of the plugin
  # Supported: ru_RU, en_US
  # Unknown languages will be replaced with en_US
  locale: en_US
  check-updates: true
  debug: false

playerCreationLimit:
  # Limit of tracked web-created discs for players without tier permissions.
  # Any value below 0 means unlimited.
  default: -1
  # Optional tiers checked via customdiscs.limit.<tier>
  vip: 20
  staff: 50

command:
  download:
    # The maximum download size in megabytes.
    max-size: 50
  create:
    local:
      custom-model: 0
    remote:
      # tabcomplete — Displaying hints when entering remote command
      # filter — Regex filter for applying custom-model-data to remote disk
      tabcomplete:
        - https://www.youtube.com/watch?v=
        - https://soundcloud.com/
      youtube:
        custom-model: 0
      soundcloud:
        custom-model: 0
  distance:
    max: 64

storage:
  # Relative directory under the plugin data folder that stores local audio files.
  local-directory: musicdata

disc:
  # The distance from which music discs can be heard in blocks.
  distance: 64
  # The master volume of music discs from 0-1.
  # You can set values like 0.5 for 50% volume.
  volume: '1.0'
  allow-hoppers: true
  lore:
    # Lore template lines for newly created discs.
    # Placeholders: {song-name}, {song-length}, {disc-creator}, {created-date}
    # Supports MiniMessage tags.
    lines:
      - '<gray>Song: <white>{song-name}'
      - '<gray>Length: <white>{song-length}'
      - '<gray>Creator: <white>{disc-creator}'
      - '<gray>Created: <white>{created-date}'
    # Date format used by {created-date} (java.time DateTimeFormatter pattern).
    date-format: yyyy-MM-dd HH:mm:ss
  deleted:
    # Name applied when a deleted tracked disc "breaks" on play attempt.
    name: '<gray>Broken Disc'
    # Lore applied when a deleted tracked disc "breaks" on play attempt.
    lore:
      - '<dark_gray>This disc is broken.'
      - '<gray>Use <white>/cd create<gray> or web upload to create a new one.'

providers:
  youtube:
    # This may help if the plugin is not working properly.
    # When you first play the disc after the server starts, you will see an authorization request in the console. Use a secondary account for security purposes.
    use-oauth2: false
    # If you have oauth2 enabled, leave these fields blank.
    # This may help if the plugin is not working properly.
    # https://github.com/lavalink-devs/youtube-source?tab=readme-ov-file#using-a-potoken
    po-token:
      token: ''
      visitor-data: ''
    # A method for obtaining streaming via a remote server that emulates a web client.
    # Make sure Oauth2 was enabled!
    # https://github.com/lavalink-devs/youtube-source?tab=readme-ov-file#using-a-remote-cipher-server
    remote-server:
      url: ''
      password: ''

web:
  # Enable the built-in HTTP upload server.
  enabled: false
  # Bind to localhost by default and place a reverse proxy in front if exposing this publicly.
  bind-address: 127.0.0.1
  port: 8080
  # URL shown to players when generating an upload token.
  public-url: http://127.0.0.1:8080/
  backlog: 16
  max-upload-size-mb: 50
  token-ttl-seconds: 600
  max-concurrent-uploads: 2
  upload:
    # Relative subdirectory inside storage.local-directory for uploaded files.
    subdirectory: web
  ui:
    # Directory under the plugin data folder that can override bundled web UI files.
    override-directory: webui
```

## Built-in web upload server

The web upload flow is disabled by default. When enabled:

1. A player runs `/cd web token`.
2. The plugin generates a short-lived one-time upload URL.
3. The player opens the page, enters the disc name, and uploads one `.mp3` file.
4. The plugin saves the file under `storage.local-directory/web.upload.subdirectory/...`.
5. The plugin reuses the same local disc creation logic as `/cd create local` to modify the disc in the player's main hand.
6. The plugin tracks these web-created discs in `data.yml` for ownership, limits, and deletion by ID.

Rules and safeguards:

- The token owner must still be online when the upload finishes.
- The token owner must be holding a music disc in the main hand when the upload finishes.
- Only `.mp3` uploads are accepted by the web server.
- Uploads are streamed to disk, limited by size, and capped by `web.max-concurrent-uploads`.
- The built-in server is designed to sit behind a reverse proxy later, but it still enforces token auth, size limits, path safety, and bounded concurrency itself.

## Web creation limits and delete flow (v1)

- Limits apply to **web-created tracked discs** only in this version.
- Effective limit = highest configured tier the player has (`customdiscs.limit.<tier>`), with `playerCreationLimit.default` as fallback.
- Any negative configured value means unlimited.
- Players can manage tracked entries with:
  - `/cd delete list [page]`
  - `/cd delete <id>`
- Deleting a tracked entry removes the source file and invalidates existing items using that tracked ID.
- On next play attempt, an invalidated tracked disc is converted to `music_disc_11` with configurable broken name/lore.
- Legacy discs (without tracked IDs) continue to work.

## Web UI customization

The plugin ships with bundled UI assets and looks for overrides in `<plugin data folder>/webui/`.

Override files:

- `webui/index.html`
- `webui/styles.css`
- `webui/app.js`
- `webui/assets/*`

Example copies of the bundled UI are written as:

- `webui/index.html.example`
- `webui/styles.css.example`
- `webui/app.js.example`

If an override file exists, the built-in web server serves that file instead of the bundled asset.

## Custom UI / API contract

The bundled UI uploads raw MP3 bytes to `POST /api/upload`.

Expected request data:

- Header or query parameter `token`
- Header or query parameter `songName`
- Header or query parameter `filename`
- Request body containing the MP3 file bytes

The bundled UI sends these headers:

- `X-CustomDiscs-Token`
- `X-CustomDiscs-Song-Name`
- `X-CustomDiscs-Filename`

Responses are JSON objects with:

- `success`
- `code`
- `message`

## Commands

```text
/cd help
/cd reload
/cd download "<direct link>" <name.extension>
/cd create <local|remote>
/cd delete <id>
/cd delete list [page]
/cd web token
/cd distance <radius>
```

Some arguments must be written in double quotes `"string"`.
