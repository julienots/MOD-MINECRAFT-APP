package com.bedrockmodstudio.core.image

/**
 * Dessins 16×16 originaux servant d'icônes de départ (l'utilisateur pourra les repeindre).
 * Légende : `.` transparent, `O` contour, `M` couleur principale, `H` reflet, `m` principale foncée,
 * `A` couleur secondaire (manche, garde…), `a` secondaire foncée.
 */
object PixelArt {
    val SWORD = listOf(
        "..............OO",
        ".............OHO",
        "............OHMO",
        "...........OHMO.",
        "..........OHMO..",
        ".........OHMO...",
        "........OHMO....",
        ".......OHMO.....",
        "..OO..OHMO......",
        "..OaOOHMO.......",
        "...OaAmO........",
        "....OAaO........",
        "...OAOOaO.......",
        "..OAO..OO.......",
        ".OAO............",
        ".OO.............",
    )
    val PICKAXE = listOf(
        "................",
        "....OOOOOO......",
        "...OHMMMMMOO....",
        "....OOOOmMMMO...",
        "........OOAmMO..",
        ".......OAO.OmMO.",
        "......OAO...OmO.",
        ".....OAO....OmO.",
        "....OAO......OO.",
        "...OAO..........",
        "..OAO...........",
        ".OAO............",
        ".OO.............",
        "................",
        "................",
        "................",
    )
    val AXE = listOf(
        "................",
        "......OOO.......",
        ".....OHMMO......",
        "....OHMMMMO.....",
        "....OMMMAAO.....",
        "....OmMOAO......",
        ".....OOAO.......",
        "......OAO.......",
        ".....OAO........",
        "....OAO.........",
        "...OAO..........",
        "..OAO...........",
        ".OAO............",
        ".OO.............",
        "................",
        "................",
    )
    val SHOVEL = listOf(
        "...........OOO..",
        "..........OHMMO.",
        "..........OMMmO.",
        "...........OmO..",
        "..........OAO...",
        ".........OAO....",
        "........OAO.....",
        ".......OAO......",
        "......OAO.......",
        ".....OAO........",
        "....OAO.........",
        "...OAO..........",
        "..OAO...........",
        "..OO............",
        "................",
        "................",
    )
    val HELMET = listOf(
        "................",
        "................",
        "................",
        "....OOOOOOOO....",
        "...OHHMMMMMMO...",
        "..OHMMMMMMMMmO..",
        "..OMMMMMMMMMmO..",
        "..OMMOOOOOOMmO..",
        "..OMO......OmO..",
        "..OMO......OmO..",
        "..OOO......OOO..",
        "................",
        "................",
        "................",
        "................",
        "................",
    )
    val CHESTPLATE = listOf(
        "................",
        "..OOOO....OOOO..",
        ".OHMMMOOOOMMMmO.",
        ".OMMMMMMMMMMMmO.",
        ".OMMMMMMMMMMMmO.",
        ".OOOOMMMMMMOOOO.",
        "....OMMAAMMO....",
        "....OMMMMMmO....",
        "....OMMMMMmO....",
        "....OMMMMMmO....",
        "....OMMMMMmO....",
        "....OOOOOOOO....",
        "................",
        "................",
        "................",
        "................",
    )
    val LEGGINGS = listOf(
        "................",
        "...OOOOOOOOOO...",
        "...OHMMAAMMmO...",
        "...OMMMMMMMmO...",
        "...OMMMOOMMmO...",
        "...OMMO..OMmO...",
        "...OMMO..OMmO...",
        "...OMMO..OMmO...",
        "...OMMO..OMmO...",
        "...OMMO..OMmO...",
        "...OOOO..OOOO...",
        "................",
        "................",
        "................",
        "................",
        "................",
    )
    val BOOTS = listOf(
        "................",
        "................",
        "................",
        "................",
        "...OOO....OOO...",
        "...OHO....OMO...",
        "...OMO....OMO...",
        "...OMO....OMO...",
        "..OMMO....OMmO..",
        ".OMMMO....OMmmO.",
        ".OAAAO....OAAAO.",
        ".OOOOO....OOOOO.",
        "................",
        "................",
        "................",
        "................",
    )
    val FOOD = listOf(
        "................",
        "........OO......",
        ".......OAO......",
        "....OOOAOOOO....",
        "...OHHMMMMMMO...",
        "..OHMMMMMMMMmO..",
        "..OHMMMMMMMMmO..",
        "..OMMMMMMMMMmO..",
        "..OMMMMMMMMMmO..",
        "..OMMMMMMMMmmO..",
        "...OMMMMMMmmO...",
        "....OmmmmmmO....",
        ".....OOOOOO.....",
        "................",
        "................",
        "................",
    )
    val GEM = listOf(
        "................",
        "................",
        ".....OOOOOO.....",
        "....OHHMMMMO....",
        "...OHHMMMMMmO...",
        "..OHMMMMMMMMmO..",
        "..OOOOOOOOOOOO..",
        "...OMMMMMMMmO...",
        "....OMMMMMmO....",
        ".....OMMMmO.....",
        "......OMmO......",
        ".......OO.......",
        "................",
        "................",
        "................",
        "................",
    )

    /** Dessine [template] avec les couleurs fournies ; renvoie une toile 16×16. */
    fun render(template: List<String>, main: Int, accent: Int): PixelCanvas {
        require(template.size == 16 && template.all { it.length == 16 }) { "Gabarit 16×16 attendu" }
        val outline = PixelCanvas.shade(main, -110).let { (it and 0x00FFFFFF) or (0xFF shl 24) }
        val c = PixelCanvas(16, 16, 0)
        template.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                c[x, y] = when (ch) {
                    'O' -> outline
                    'M' -> main
                    'H' -> PixelCanvas.shade(main, 60)
                    'm' -> PixelCanvas.shade(main, -45)
                    'A' -> accent
                    'a' -> PixelCanvas.shade(accent, -45)
                    else -> 0
                }
            }
        }
        return c
    }
}
