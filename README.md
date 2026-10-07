# DeeXLava

Lavalink v4 plugin that adds a Deezer source. Author: **Pawan**

## Install (JitPack)

```yaml
lavalink:
  plugins:
    - dependency: "com.github.<your-github-user>:DeeXLava:<TAG>"
      repository: "https://jitpack.io"
```

## Enable

```yaml
plugins:
  deexlava:
    engine: enable
```

If you also use LavaSrc, turn its Deezer source off to avoid two sources fighting over deezer.com links:

```yaml
plugins:
  lavasrc:
    sources:
      deezer: false
```

## What it loads

| Input | Example |
| --- | --- |
| Search | `dzsearch:daft punk one more time` |
| ISRC | `dzisrc:GBDUW0000059` |
| Track / album / playlist / artist URL | `https://www.deezer.com/track/3135556` |
| Short link | `https://link.deezer.com/s/XXXX` |

## Playback modes

1. **Mirror (default, no login needed):** metadata comes from Deezer, audio is played from another source
   using `providers` (ISRC first, then title + artist). Needs a search source such as the YouTube plugin.
2. **Direct:** set `arl` and `masterDecryptionKey` (you must supply both). The plugin requests the stream
   from Deezer, decrypts it on the fly and plays FLAC/MP3 by your `formats` order. If direct playback
   fails it falls back to the mirror. Use only an account you own; this may be against Deezer's terms.

## Options (`plugins.deexlava.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `engine` | `enable` | `disable` turns the source off |
| `sourceName` | `deexlava` | name reported to clients |
| `arl` | empty | Deezer login cookie for direct playback |
| `masterDecryptionKey` | empty | exactly 16 characters |
| `formats` | `FLAC, MP3_320, MP3_256, MP3_128` | first allowed one wins |
| `searchLimit` | 10 | max 50 |
| `playlistLimit` | 100 | tracks per album/playlist |
| `artistTopLimit` | 25 | tracks for artist URLs |
| `mirrorEnabled` | true | fallback to other sources |
| `providers` | yt/ytm ISRC + query | `%ISRC%` and `%QUERY%` placeholders |

## Build

```
./gradlew build        # jar in build/libs/deexlava-1.0.0.jar
```

JitPack: push to GitHub, create a release tag, then look the repo up on jitpack.io.
# DeeXLava
