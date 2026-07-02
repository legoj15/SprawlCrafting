package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.util.RecipeItemHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.network.play.server.SPacketPlaceGhostRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.crafting.IShapedRecipe;

/**
 * Server-side, atomic recipe placement into the player's open crafting grid — the engine behind
 * the JEI "+" button and the final-step grid hand-off. A faithful port of vanilla's
 * {@code ServerRecipeBookHelper} algorithm (return the grid to the inventory first, count from the
 * server's authoritative inventory, shift = biggest craftable stack, non-shift on a matching grid
 * increments by one, clamp to the smallest ingredient max-stack, move items one at a time), with
 * three deliberate differences:
 *
 * <ul>
 *   <li><b>Generic over any grid.</b> Vanilla hardcodes result-slot-0/grid-1..9 and dispatches on
 *       {@code ContainerWorkbench}/{@code ContainerPlayer}/{@code IRecipeContainer}; this placer
 *       finds the grid structurally — the container slots backed by an {@link InventoryCrafting} —
 *       and addresses cells by each slot's index into that matrix, so a Tinkers' Construct Crafting
 *       Station (or any modded 3x3) works with no layout assumption at all.</li>
 *   <li><b>No recipe-book unlock gate.</b> JEI's own transfer has none either; requiring unlocks
 *       here would make the "+" button silently dead for recipes the player simply hasn't
 *       discovered yet.</li>
 *   <li><b>The width/height transposition in vanilla's distribution loops is fixed</b> (vanilla
 *       compares the row counter against the recipe height but bounds it by the grid width — only
 *       safe because vanilla grids are square; a structural grid need not be).</li>
 * </ul>
 *
 * <p>Everything here mutates only server-authoritative state in one code path — no cursor stack,
 * no click transactions, no client prediction — which is exactly what makes it immune to the
 * under/overfill desyncs of the old client-side drag simulation (kept only as the modless-server
 * fallback in the JEI handler).
 */
public final class ServerGridPlacer {

    private ServerGridPlacer() {
    }

    /** Outcome of the final-step hand-off attempt. */
    public enum HandOffResult {
        /** The final craft's ingredients are laid in the grid; the result is takeable. */
        FILLED,
        /** Could not fill (grid busy/too small/materials missing) — auto-craft instead. */
        FALLBACK
    }

    /**
     * Packet-path entry for the JEI "+" click. Validates against the live open container (the
     * client's view may be stale) and then places with full vanilla recipe-book semantics,
     * including the clear-grid-and-ghost fallback when materials have vanished since the client
     * enabled the button.
     */
    public static void handlePlaceRecipe(EntityPlayerMP player, ResourceLocation recipeId,
                                         int windowId, boolean maxTransfer) {
        if (player.isSpectator()) {
            return;
        }
        Container container = player.openContainer;
        if (container == null || container.windowId != windowId || !container.getCanCraft(player)) {
            return;
        }
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            return;
        }
        placeRecipe(player, recipe, maxTransfer);
    }

    /**
     * Places {@code recipe} into the open container's grid with vanilla semantics. Returns false
     * (leaving a ghost outline where the vanilla GUI supports one) when the materials aren't there.
     */
    public static boolean placeRecipe(EntityPlayerMP player, IRecipe recipe, boolean maxTransfer) {
        Container container = player.openContainer;
        Grid grid = Grid.of(container);
        if (grid == null || !recipe.canFit(grid.width(), grid.height())) {
            return false;
        }
        // Vanilla gate: the current grid contents must be returnable to the inventory (creative
        // players are exempt; their overflow drops at their feet, which vanilla also tolerates).
        if (!canReturnGridToInventory(player, grid) && !player.isCreative()) {
            return false;
        }
        RecipeItemHelper helper = new RecipeItemHelper();
        player.inventory.fillStackedContents(helper, false);
        accountGrid(grid, helper);
        accountStationChest(player, helper);

        if (!helper.canCraft(recipe, null)) {
            // Vanilla parity: clear the grid and show the recipe as a ghost instead of a partial
            // fill. Vanilla GUIs render it; a modded station without the recipe-book listener
            // simply ignores the packet, so this is safe to send unconditionally.
            returnGridToInventory(player, grid);
            player.connection.sendPacket(new SPacketPlaceGhostRecipe(container.windowId, recipe));
            finishSync(player, container);
            return false;
        }
        place(player, grid, recipe, helper, maxTransfer);
        finishSync(player, container);
        return true;
    }

    /**
     * Final-step hand-off: lay exactly ONE craft of {@code recipe} into the open grid, sourcing
     * from the player inventory and the open station's bound chest, and verify the grid genuinely
     * matches the recipe afterwards — reverting and reporting {@link HandOffResult#FALLBACK} on
     * any doubt, so the engine's auto-craft (the only place the item is granted) still runs and
     * nothing is ever lost.
     */
    public static HandOffResult placeFinalCraft(EntityPlayerMP player, IRecipe recipe) {
        if (player.isSpectator()) {
            return HandOffResult.FALLBACK;
        }
        Container container = player.openContainer;
        Grid grid = Grid.of(container);
        if (grid == null || !recipe.canFit(grid.width(), grid.height())
                || !canReturnGridToInventory(player, grid)) {
            return HandOffResult.FALLBACK;
        }
        RecipeItemHelper helper = new RecipeItemHelper();
        player.inventory.fillStackedContents(helper, false);
        accountGrid(grid, helper);
        accountStationChest(player, helper);
        IntList packed = new IntArrayList();
        if (!helper.canCraft(recipe, packed, 1)) {
            return HandOffResult.FALLBACK;
        }
        returnGridToInventory(player, grid);
        distribute(player, grid, recipe, 1, packed);
        container.onCraftMatrixChanged(grid.matrix);
        if (!recipe.matches(grid.matrix, player.world)) {
            // The placement didn't produce a takeable craft (ingredient raced away, oddball
            // recipe). Put everything back and let the auto-craft path handle it.
            returnGridToInventory(player, grid);
            finishSync(player, container);
            return HandOffResult.FALLBACK;
        }
        finishSync(player, container);
        return HandOffResult.FILLED;
    }

    /** Vanilla {@code func_194329_b}: early-out, pick the count, clamp, clear, distribute. */
    private static void place(EntityPlayerMP player, Grid grid, IRecipe recipe,
                              RecipeItemHelper helper, boolean maxTransfer) {
        boolean alreadyMatches = recipe.matches(grid.matrix, player.world);
        int biggest = helper.getBiggestCraftableStack(recipe, null);

        if (alreadyMatches) {
            // Grid already shows this recipe with every stack at its cap — nothing to add.
            boolean atCap = true;
            for (int i = 0; i < grid.matrix.getSizeInventory(); i++) {
                ItemStack stack = grid.matrix.getStackInSlot(i);
                if (!stack.isEmpty() && Math.min(biggest, stack.getMaxStackSize()) > stack.getCount()) {
                    atCap = false;
                    break;
                }
            }
            if (atCap) {
                return;
            }
        }

        int count = placeCount(grid, biggest, alreadyMatches, maxTransfer);
        IntList packed = new IntArrayList();
        if (!helper.canCraft(recipe, packed, count)) {
            return;
        }
        // Clamp to the smallest max-stack among the concrete items chosen (a shift-fill of 3
        // ender pearls + 3 blaze powder must stop at 16, the pearls' stack cap). Looped until the
        // pick is stable: re-picking at a lower count can select DIFFERENT concrete items with an
        // even lower cap (an oredict slot satisfied by an unstackable alternative), and vanilla's
        // single-pass clamp would then overstack a grid cell. Terminates — count strictly
        // decreases and every max-stack is >= 1.
        int clamped;
        while ((clamped = smallestMaxStack(packed, count)) < count) {
            count = clamped;
            packed.clear();
            if (!helper.canCraft(recipe, packed, count)) {
                return;
            }
        }
        returnGridToInventory(player, grid);
        distribute(player, grid, recipe, count, packed);
        player.openContainer.onCraftMatrixChanged(grid.matrix);
    }

    /**
     * Counts the grid's contents into the helper through the container's slots rather than
     * vanilla's {@code InventoryCrafting.fillStackedContents}, which iterates the base class's
     * private backing list — permanently empty for a Tinkers' Construct-style persistent grid
     * that stores its stacks in the tile behind overridden accessors. Reading via
     * {@link Slot#getStack} goes through those overrides, so every grid is counted correctly;
     * for a vanilla matrix the two are behaviorally identical (same per-stack
     * {@code accountStack} loop and filters).
     */
    private static void accountGrid(Grid grid, RecipeItemHelper helper) {
        for (Slot cell : grid.cells) {
            helper.accountStack(cell.getStack());
        }
    }

    private static int smallestMaxStack(IntList packed, int cap) {
        int smallest = cap;
        for (int i = 0; i < packed.size(); i++) {
            int max = RecipeItemHelper.unpack(packed.getInt(i)).getMaxStackSize();
            if (max < smallest) {
                smallest = max;
            }
        }
        return smallest;
    }

    /**
     * Vanilla {@code func_194324_a}: shift = the biggest craftable stack; a plain click on a grid
     * already holding this recipe adds one more per slot (the vanilla incremental-click feel);
     * otherwise a single craft.
     */
    private static int placeCount(Grid grid, int biggest, boolean alreadyMatches, boolean maxTransfer) {
        if (maxTransfer) {
            return biggest;
        }
        if (alreadyMatches) {
            int smallest = 64;
            for (int i = 0; i < grid.matrix.getSizeInventory(); i++) {
                ItemStack stack = grid.matrix.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getCount() < smallest) {
                    smallest = stack.getCount();
                }
            }
            if (smallest < 64) {
                smallest++;
            }
            return smallest;
        }
        return 1;
    }

    /**
     * Vanilla {@code func_194323_a} with the transposition fixed: walk grid rows/columns top-left
     * anchored, consuming one packed ingredient per cell inside the recipe's width/height, and move
     * {@code count} items into each occupied cell one at a time from the player inventory.
     */
    private static void distribute(EntityPlayerMP player, Grid grid, IRecipe recipe,
                                   int count, IntList packed) {
        int gridWidth = grid.width();
        int recipeWidth = gridWidth;
        int recipeHeight = grid.height();
        if (recipe instanceof IShapedRecipe) {
            recipeWidth = ((IShapedRecipe) recipe).getRecipeWidth();
            recipeHeight = ((IShapedRecipe) recipe).getRecipeHeight();
        }
        Iterator<Integer> iterator = packed.iterator();
        for (int row = 0; row < grid.height() && row != recipeHeight; row++) {
            for (int col = 0; col < gridWidth; col++) {
                if (col == recipeWidth || !iterator.hasNext()) {
                    break;
                }
                Slot cell = grid.cell(row * gridWidth + col);
                ItemStack ingredient = RecipeItemHelper.unpack(iterator.next().intValue());
                if (ingredient.isEmpty()) {
                    continue;
                }
                for (int n = 0; n < count; n++) {
                    moveOneIntoCell(player, cell, ingredient);
                }
            }
            if (!iterator.hasNext()) {
                break;
            }
        }
    }

    /**
     * Vanilla {@code func_194325_a}, extended with the station chest: take one matching unused
     * item (never damaged, enchanted, or renamed — vanilla's
     * {@link InventoryPlayer#findSlotMatchingUnusedItem} predicate) from the player inventory
     * first, then from the open station's bound chest, into the grid cell. The first item goes
     * through {@link Slot#putStack} so the container's matrix-changed hook fires (and a persistent
     * station's write-through applies).
     */
    private static void moveOneIntoCell(EntityPlayerMP player, Slot cell, ItemStack ingredient) {
        InventoryPlayer inventory = player.inventory;
        int slot = inventory.findSlotMatchingUnusedItem(ingredient);
        ItemStack taken;
        if (slot != -1) {
            taken = inventory.getStackInSlot(slot).copy();
            if (taken.isEmpty()) {
                return;
            }
            if (taken.getCount() > 1) {
                inventory.decrStackSize(slot, 1);
            } else {
                inventory.removeStackFromSlot(slot);
            }
        } else {
            taken = takeOneFromStationChest(player, ingredient);
            if (taken.isEmpty()) {
                return;
            }
        }
        taken.setCount(1);
        if (cell.getStack().isEmpty()) {
            cell.putStack(taken);
        } else {
            cell.getStack().grow(1);
        }
    }

    /**
     * Counts the open station's bound-chest slots into the helper — the same pool the engine
     * consumes ({@link ExternalSlots#of}; empty unless a recognised 3x3 station is open). Verified
     * against stock behavior 2026-07-02: TConstruct's own JEI integration moves chest items into
     * the grid, so a placer that ignored the chest was a REGRESSION at stations, not vanilla
     * parity — and it silently downgraded every chest-fed final-step hand-off to an auto-craft.
     */
    private static void accountStationChest(EntityPlayerMP player, RecipeItemHelper helper) {
        for (Slot slot : ExternalSlots.of(player)) {
            helper.accountStack(slot.getStack());
        }
    }

    /**
     * Chest counterpart of {@code findSlotMatchingUnusedItem}: same predicate (item match, never
     * damaged/enchanted/renamed), consuming through {@link Slot#decrStackSize} so a station's
     * item-handler write-through applies. Deposits still only ever go to the player —
     * consume-from-chest, deposit-to-player, like the engine.
     */
    private static ItemStack takeOneFromStationChest(EntityPlayerMP player, ItemStack ingredient) {
        for (Slot slot : ExternalSlots.of(player)) {
            ItemStack inSlot = slot.getStack();
            if (!inSlot.isEmpty() && inSlot.getItem() == ingredient.getItem()
                    && CraftExecutor.usableAsIngredient(inSlot)) {
                ItemStack taken = inSlot.copy();
                slot.decrStackSize(1);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Whether the grid's current contents would all fit back into the player's inventory —
     * counting, like vanilla's {@code storeItemStack}-based gate, a merge into a matching partial
     * offhand stack as well as the 36 main slots. (Items merged into the offhand are then
     * invisible to the placement's own counting and pulls, exactly as in vanilla: both
     * {@code fillStackedContents(helper, false)} and {@code findSlotMatchingUnusedItem} are
     * main-inventory-only.)
     */
    private static boolean canReturnGridToInventory(EntityPlayerMP player, Grid grid) {
        List<ItemStack> contents = new ArrayList<ItemStack>();
        for (Slot cell : grid.cells) {
            ItemStack stack = cell.getStack();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        if (contents.isEmpty()) {
            return true;
        }
        ItemStack offhand = player.inventory.offHandInventory.get(0).copy();
        List<ItemStack> remainder = new ArrayList<ItemStack>();
        for (ItemStack stack : contents) {
            mergeIntoOffhand(offhand, stack);
            if (!stack.isEmpty()) {
                remainder.add(stack);
            }
        }
        return remainder.isEmpty() || CraftExecutor.fitsInMainInventory(player.inventory, remainder);
    }

    /**
     * Empties the grid into the inventory — matching partial offhand stack first (vanilla
     * {@code storeItemStack} order), then the 36 main slots; overflow drops at the player's feet
     * (only reachable for creative players, who skip the fit pre-check, matching vanilla's
     * tolerance). Clearing through {@link Slot#putStack} fires the matrix-changed hook per cell,
     * so the result slot recomputes to empty without the explicit result-clear vanilla needs.
     */
    private static void returnGridToInventory(EntityPlayerMP player, Grid grid) {
        ItemStack offhand = player.inventory.offHandInventory.get(0);
        for (Slot cell : grid.cells) {
            ItemStack stack = cell.getStack();
            if (!stack.isEmpty()) {
                cell.putStack(ItemStack.EMPTY);
                mergeIntoOffhand(offhand, stack);
                if (!stack.isEmpty()) {
                    CraftExecutor.insertIntoMainInventory(player, stack);
                }
            }
        }
    }

    /** Merges as much of {@code stack} into a matching, non-full, stackable offhand stack. */
    private static void mergeIntoOffhand(ItemStack offhand, ItemStack stack) {
        if (offhand.isEmpty() || !offhand.isStackable() || !CraftExecutor.sameItem(offhand, stack)) {
            return;
        }
        int room = offhand.getMaxStackSize() - offhand.getCount();
        if (room > 0) {
            int moved = Math.min(room, stack.getCount());
            offhand.grow(moved);
            stack.shrink(moved);
        }
    }

    private static void finishSync(EntityPlayerMP player, Container container) {
        player.inventory.markDirty();
        container.detectAndSendChanges();
    }

    /**
     * The open container's crafting grid, addressed by matrix cell index via each slot's own index
     * into the backing {@link InventoryCrafting} — no assumption about where in the container the
     * grid slots sit. Null when the container exposes no complete matrix (then there is nothing a
     * placement could safely write to).
     */
    private static final class Grid {
        final InventoryCrafting matrix;
        final List<Slot> cells;
        private final Slot[] byMatrixIndex;

        private Grid(InventoryCrafting matrix, List<Slot> cells, Slot[] byMatrixIndex) {
            this.matrix = matrix;
            this.cells = cells;
            this.byMatrixIndex = byMatrixIndex;
        }

        static Grid of(Container container) {
            if (container == null) {
                return null;
            }
            InventoryCrafting matrix = null;
            List<Slot> cells = new ArrayList<Slot>();
            for (Slot slot : container.inventorySlots) {
                if (slot.inventory instanceof InventoryCrafting) {
                    if (matrix == null) {
                        matrix = (InventoryCrafting) slot.inventory;
                    }
                    if (slot.inventory == matrix) {
                        cells.add(slot);
                    }
                }
            }
            if (matrix == null) {
                return null;
            }
            int size = matrix.getWidth() * matrix.getHeight();
            Slot[] byMatrixIndex = new Slot[size];
            for (Slot slot : cells) {
                int index = slot.getSlotIndex();
                if (index < 0 || index >= size || byMatrixIndex[index] != null) {
                    return null;
                }
                byMatrixIndex[index] = slot;
            }
            // Every matrix cell must be reachable through a container slot, or a placement could
            // write items the container never shows (and the player could never take back).
            for (Slot slot : byMatrixIndex) {
                if (slot == null) {
                    return null;
                }
            }
            return new Grid(matrix, cells, byMatrixIndex);
        }

        Slot cell(int matrixIndex) {
            return byMatrixIndex[matrixIndex];
        }

        int width() {
            return matrix.getWidth();
        }

        int height() {
            return matrix.getHeight();
        }
    }
}
