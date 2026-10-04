---
name: voxelprobe-build
description: Create or edit Minecraft 1.20.1 building files and place or verify them in a local Forge world using VoxelProbe.
license: MIT
---

Use VoxelProbe's `minecraft_export` and `minecraft_place` MCP tools. The package includes the exact JSON schema in `tool/building.py` and a working `tool/example_build.json`. Exports target **Minecraft 1.20.1 / DataVersion 3465**.

- Use complete namespaced block states. Block-entity `nbt` is an **SNBT string**, preserving byte/int/long and array types. Preserve inventories and machine data when requested.
- Read warnings when importing files. Unsupported entities, scheduled ticks, biomes or data versions are rejected; do not silently strip them.
- `minecraft_export(spec_path, output_dir, formats)` writes `.litematic`, Sponge v2 `.schem` or vanilla structure `.nbt`. Existing output files are protected; use a new revision path or an explicitly authorized replacement.
- Before `minecraft_place`, use `minecraft_read(operation="status")` to confirm the instance and world. Placement offsets logical coordinates, skips air by default and caps each call at 65,536 blocks. Large builds can use WorldEdit import when installed; native `.nbt` placement anchors the minimum corner.
- For appearance or behavior, place in a test world, inspect `minecraft_view` frames and read back state/NBT. File round trips alone cannot establish geometry quality, redstone timing or save persistence.

The installer binds MCP to `MINECRAFT_GAME_DIR`. For CLI fallback, use the package's `tool/.venv` interpreter with `tool/mc.py --game-dir <instance>`, followed by the desired subcommand. Connection tokens remain in the instance's private connection file; do not read or share them.

SPDX-License-Identifier: MIT
