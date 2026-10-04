---
name: voxelprobe-inspect
description: Inspect or edit a local Minecraft 1.20.1 Forge world through VoxelProbe, including server state, NBT, Groovy scripts and real game frames.
license: MIT
---

Use the VoxelProbe MCP server's `minecraft_read`, `minecraft_write`, `minecraft_script` and `minecraft_view` tools. Start with `minecraft_read(operation="status")` and confirm the instance directory, Minecraft version and intended world before editing.

The installer binds this server to `MINECRAFT_GAME_DIR`. The mod creates a private `config/debugbridge-connection.json` in that instance after being enabled. If unavailable, ask the user to start that configured instance and enable VoxelProbe. Never read or print the token, paste it into agent context, or copy it into MCP configuration.

- World reads and edits use the **integrated server**; a remote multiplayer client does not grant access to its server. Honor pagination, `loaded:false` and truncation markers. Block NBT includes container contents.
- `minecraft_write` supports batch edits, commands, saving structures/worlds and tick observations. A batch entry is `[x,y,z,"block[state]","optional SNBT"]`. Validation does not provide transactional rollback.
- `minecraft_script` defaults to server scope with `server`, `world`/`level` and `player`. Client scope exposes `mc`, `player` and `level`. Use Mojang member names. `sync { ... }` runs bounded work on the selected game thread. A timeout does not prove a mutation stopped; read back before retrying.
- `minecraft_view` shows the rendered framebuffer. Use state/NBT reads for world facts and actual frames for appearance; save/reopen when persistence matters. Test risky edits in a disposable world or a copy.

The package README documents operation arguments. For CLI fallback, use the package's `tool/.venv` interpreter with `tool/mc.py --game-dir <instance> read status`; JSON arguments use `--input payload.json`, and Groovy uses `script code.groovy`.

SPDX-License-Identifier: MIT
