package com.debugbridge.core.protocol.dto;

/**
 * One item in a block-entity container ({@code blockDetails.items[]}).
 *
 * <p>Note: deliberately distinct from {@link ItemStackDto}. Block-container
 * items carry a flat {@code slot} index, whereas screenInspect uses a wrapper
 * {@link SlotDto} around an {@link ItemStackDto}. {@code itemId} is the
 * canonical registry-key form (e.g. {@code "minecraft:diamond"}), matching
 * every other {@code itemId} on the wire and accepted directly by the
 * texture-fetch endpoints.
 *
 * <p><b>Client-side visibility caveat.</b> Vanilla Minecraft does not sync
 * the contents of most {@code Container} BlockEntities to the client —
 * chests, trapped chests, barrels, hoppers, dispensers, droppers, furnaces
 * (and variants), brewing stands, crafters, and shulker boxes all keep
 * their items server-only. The provider walks {@code container.getItem(i)}
 * on a client-side BlockEntity, so for those types {@code blockDetails}
 * returns {@code containerSize} correctly but {@code items: []}. To see
 * items actually held by those BlockEntities, open the menu and read
 * {@code screenInspect.slots[*].item} (which fires {@link ItemStackDto}).
 *
 * <p>BlockEntities that <i>do</i> sync items to the client — and therefore
 * <i>do</i> populate this DTO — are the ones whose items participate in
 * world rendering: lecterns (the held book), chiseled bookshelves (visible
 * book spines), and jukeboxes (the disc on top). Modded BlockEntities that
 * override {@code getUpdateTag()} to push items will also light this path.
 */
public final class BlockItemDto {
    public int slot;
    public String itemId;
    public int count;
    public Integer damage;
    public Integer maxDamage;
    public String name;
}
