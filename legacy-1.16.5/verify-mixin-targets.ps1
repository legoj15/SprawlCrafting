<#
.SYNOPSIS
  javap-verify every recipe-book mixin target member against the 1.16.5 NAMED Minecraft jars that the
  Forge (FG7 mavenizer, "official") and Fabric (Loom layered Mojmap + Forge class names) builds compile
  against. Prints OK/MISSING per member per jar; exit 0 only if every member is found in both.

.NOTES
  Run after a build of both loaders (the jars are produced by :forge:compileJava / :fabric:compileJava).
  When a mixin gains a new target member, add it to $checks below.
#>
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$javap = 'C:\Program Files\Java\jdk-21.0.2\bin\javap.exe'

$forgeJar = Join-Path $root '.gradle\mavenizer\repo\net\minecraftforge\forge\1.16.5-36.2.42\forge-1.16.5-36.2.42.jar'
$fabricJar = Get-ChildItem -Recurse -Filter 'minecraft-merged-*.jar' (Join-Path $root '.gradle\loom-cache\minecraftMaven') |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName

$pkg = 'net.minecraft.client.gui.recipebook'
# class => members, written exactly as `javap -p` prints them (generic signatures included), plus
# "INVOKE <owner>.<name>:<desc>" lines that must appear in `javap -c` (redirect / INVOKE targets).
$checks = [ordered]@{
    "$pkg.RecipeWidget" = @(
        'private net.minecraft.client.gui.recipebook.RecipeList collection;',
        'public void renderButton(com.mojang.blaze3d.matrix.MatrixStack, int, int, float);',
        'public java.util.List<net.minecraft.util.text.ITextComponent> getTooltipText(net.minecraft.client.gui.screen.Screen);',
        'public boolean isOnlyOption();',
        'public net.minecraft.item.crafting.IRecipe<?> getRecipe();',
        'INVOKE net/minecraft/client/gui/recipebook/RecipeWidget.blit:(Lcom/mojang/blaze3d/matrix/MatrixStack;IIIIII)V'
    )
    "$pkg.RecipeBookGui" = @(
        'private final net.minecraft.client.gui.recipebook.RecipeBookPage recipeBookPage;',
        'protected net.minecraft.inventory.container.RecipeBookContainer<?> menu;',
        'protected final net.minecraft.client.gui.recipebook.GhostRecipe ghostRecipe;',
        'public void renderGhostRecipe(com.mojang.blaze3d.matrix.MatrixStack, int, int, boolean, float);',
        'public boolean mouseClicked(double, double, int);',
        'public void init(int, int, net.minecraft.client.Minecraft, boolean, net.minecraft.inventory.container.RecipeBookContainer<?>);',
        'public void recipesUpdated();',
        'public boolean isVisible();',
        'public void setupGhostRecipe(net.minecraft.item.crafting.IRecipe<?>, java.util.List<net.minecraft.inventory.container.Slot>);',
        'INVOKE net/minecraft/client/multiplayer/PlayerController.handlePlaceRecipe:(ILnet/minecraft/item/crafting/IRecipe;Z)V'
    )
    "$pkg.RecipeList" = @(
        'private final java.util.Set<net.minecraft.item.crafting.IRecipe<?>> craftable;',
        'public void canCraft(net.minecraft.item.crafting.RecipeItemHelper, int, int, net.minecraft.item.crafting.RecipeBook);',
        'public java.util.List<net.minecraft.item.crafting.IRecipe<?>> getRecipes(boolean);',
        'public boolean isCraftable(net.minecraft.item.crafting.IRecipe<?>);'
    )
    "$pkg.RecipeBookPage" = @(
        'private net.minecraft.client.gui.recipebook.RecipeWidget hoveredButton;',
        'private final net.minecraft.client.gui.recipebook.RecipeOverlayGui overlay;',
        'public net.minecraft.item.crafting.IRecipe<?> getLastClickedRecipe();',
        'public net.minecraft.client.gui.recipebook.RecipeList getLastClickedRecipeCollection();'
    )
    "$pkg.RecipeOverlayGui" = @(
        'private final java.util.List<net.minecraft.client.gui.recipebook.RecipeOverlayGui$RecipeButtonWidget> recipeButtons;',
        'public boolean isVisible();',
        'public boolean mouseClicked(double, double, int);'
    )
    "$pkg.RecipeOverlayGui`$RecipeButtonWidget" = @(
        'private final net.minecraft.item.crafting.IRecipe<?> recipe;',
        'private final boolean isCraftable;'
    )
}

$missing = 0
foreach ($jar in @(@{ name = 'forge'; path = $forgeJar }, @{ name = 'fabric'; path = $fabricJar })) {
    if (-not $jar.path -or -not (Test-Path $jar.path)) { "MISSING-JAR $($jar.name)"; $missing++; continue }
    foreach ($cls in $checks.Keys) {
        $decl = (& $javap -p -cp $jar.path $cls) -join "`n"
        $code = $null
        foreach ($member in $checks[$cls]) {
            if ($member.StartsWith('INVOKE ')) {
                if (-not $code) { $code = (& $javap -p -c -cp $jar.path $cls) -join "`n" }
                $ref = $member.Substring(7)
                $found = $code.Contains('// Method ' + $ref)
                # javap drops the owner when the call targets the inspected class itself.
                $selfOwner = ($cls -replace '\.', '/') + '.'
                if (-not $found -and $ref.StartsWith($selfOwner)) {
                    $found = $code.Contains('// Method ' + $ref.Substring($selfOwner.Length))
                }
            } else {
                $found = $decl.Contains('  ' + $member)
            }
            if (-not $found) { $missing++ }
            '{0,-7} {1,-6} {2} :: {3}' -f ($(if ($found) { 'OK' } else { 'MISSING' })), $jar.name, $cls.Substring($pkg.Length + 1), $member
        }
    }
}
"forgeJar=$forgeJar"
"fabricJar=$fabricJar"
if ($missing -eq 0) { 'ALL TARGETS PRESENT'; exit 0 } else { "$missing MISSING"; exit 1 }
