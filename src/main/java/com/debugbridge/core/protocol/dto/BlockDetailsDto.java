package com.debugbridge.core.protocol.dto;

import java.util.List;

/**
 * Wire shape for the {@code blockDetails} endpoint.
 *
 * <p>Two emit shapes:
 * <ul>
 *   <li><b>Gone</b> — block entity no longer exists at the queried position.
 *       Construct via {@link #gone()}; serializes to {@code {"gone": true}}.
 *   <li><b>Present</b> — populated by the provider. {@code x/y/z}, {@code type},
 *       {@code blockId} are always set when present. Type-specific blocks set
 *       {@code signLines}/{@code signLinesBack}/{@code isWaxed} (signs) or
 *       {@code items}/{@code containerSize} (containers). All optional fields
 *       are omitted on the wire when null via {@code GSON_OMIT_NULLS}.
 * </ul>
 *
 * <p>{@code type} carries a runtime BlockEntity class name; the handler runs
 * it through the mapping resolver before serialization.
 */
public final class BlockDetailsDto {
    public Boolean gone;
    public Integer x;
    public Integer y;
    public Integer z;
    public String type;
    public String blockId;

    // Sign-specific (1.21.11 emits signLinesBack + isWaxed; 1.19 omits both).
    public List<String> signLines;
    public List<String> signLinesBack;
    public Boolean isWaxed;

    // Container-specific. {@code containerSize} is populated whenever the
    // BlockEntity implements {@code Container} (since the size is part of
    // class shape and always available client-side). {@code items} is only
    // populated when the BlockEntity actually syncs its items to the
    // client — lecterns, chiseled bookshelves, jukeboxes — i.e. block
    // types whose items are part of world rendering. Chest / hopper /
    // dispenser / furnace / brewing-stand etc. contents are server-only
    // in vanilla and surface here as {@code items: []}; use
    // {@code screenInspect} on the open menu to read those.
    public List<BlockItemDto> items;
    public Integer containerSize;

    public static BlockDetailsDto gone() {
        BlockDetailsDto d = new BlockDetailsDto();
        d.gone = true;
        return d;
    }
}
