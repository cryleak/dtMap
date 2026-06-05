package com.ricedotwho.dtmap.features

import com.ricedotwho.dtmap.DtMap.mc
import com.ricedotwho.dtmap.config.C1Map
import com.ricedotwho.dtmap.features.map.Room
import com.ricedotwho.dtmap.features.map.Scan
import com.ricedotwho.dtmap.features.map.Scoreboard
import com.ricedotwho.dtmap.gui.Hud
import com.ricedotwho.dtmap.utils.Location
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color

object PuzzleHud : Hud.Component("PuzzleHud", 0.4, 0.4, Hud.Type.Dungeon, staticRenderConditions = mutableListOf(Hud.Condition.BeforeMort, Hud.Condition.Alt)) {
    override fun render(context: GuiGraphics) {
        if (Location.island != Location.Island.Dungeon) return

        context.drawString(mc.font, "Puzzles(${Scoreboard.stats.puzzleCount}):", 0, 0, Color.WHITE.rgb)

        var undiscoveredPuzzleCount: Int

        if (SoloClear.legit(C1Map.legitMode)) {
            Scoreboard.stats.puzzles.forEachIndexed { index, puzzle ->
                val height = (index + 1) * (mc.font.lineHeight + 2)

                val color = if (puzzle.status == Scoreboard.PuzzleStatus.Completed) Color.GREEN else Color.RED
                context.drawString(mc.font, puzzle.displayName, 0, height, color.rgb)
            }

            undiscoveredPuzzleCount = Scoreboard.stats.puzzleCount - Scoreboard.stats.puzzles.size
        } else {
            Scan.puzzles.forEachIndexed { index, puzzle ->
                val height = (index + 1) * (mc.font.lineHeight + 2)

                val color = if (puzzle.state == Room.State.GREEN || puzzle.state == Room.State.CLEARED) Color.GREEN else Color.RED
                val name = if (puzzle.data == null) "???" else puzzle.data!!.name
                context.drawString(mc.font, name, 0, height, color.rgb)
            }

            undiscoveredPuzzleCount = Scoreboard.stats.puzzleCount - Scan.puzzles.size
        }

        for (index in Scoreboard.stats.puzzleCount - undiscoveredPuzzleCount until Scoreboard.stats.puzzleCount) {
            val height = (index + 1) * (mc.font.lineHeight + 2)
            context.drawString(mc.font, "???", 0, height, Color.RED.rgb)
        }
    }

    override fun example(context: GuiGraphics) {
        context.drawString(mc.font, "Puzzles(3):", 0, 0, Color.WHITE.rgb)
        context.drawString(mc.font, "Tic Tac Toe", 0, mc.font.lineHeight + 2, Color.GREEN.rgb)
        context.drawString(mc.font, "Quiz", 0, (mc.font.lineHeight + 2) * 2, Color.RED.rgb)
        context.drawString(mc.font, "???", 0, (mc.font.lineHeight + 2) * 3, Color.RED.rgb)
    }

    override fun bounds(): Pair<Double, Double> =
        Pair(mc.font.width("Tic Tac Toe").toDouble(), mc.font.lineHeight.toDouble() * 4 + 2 * 3)
}