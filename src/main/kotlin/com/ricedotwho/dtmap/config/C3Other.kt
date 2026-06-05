package com.ricedotwho.dtmap.config

import com.ricedotwho.dtmap.gui.Setting
import com.ricedotwho.dtmap.gui.Tab

@Tab("Other")
object C3Other {

    @Setting("Key Pickup Pling", lineBefore = true)
    var keyPickupPling = false

    @Setting("Volume", min = 0.1, max = 2.0)
    var keyPickupPlingVolume = 1.0f

    @Setting("Redstone Key Skull Highlight", lineBefore = true)
    var redstoneKeySkullHighlight = false

    @Setting("Solo Clearing", lineBefore = true, combo = ["No", "Auto", "Yes"])
    var soloClearing = 0

    @Setting("Hud Insight Peak")
    var soloClearHudInsight = false

    @Setting("Insta Join On Menu Open")
    var soloClearingInstaJoin = false

    @Setting("Only show checkmarks")
    var soloClearingOnlyShowCheckmarks = false

    @Setting("Instant Room Update")
    var soloClearingInstantRoomUpdate = false

    @Setting("Door Esp")
    var soloClearingDoorEsp = false
}
