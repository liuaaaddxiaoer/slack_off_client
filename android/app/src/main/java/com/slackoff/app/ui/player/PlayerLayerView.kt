package com.slackoff.app.ui.player

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView as ExoPlayerView

@Composable
fun PlayerLayerView(
    player: Player,
    modifier: Modifier = Modifier,
    useController: Boolean = false
) {
    AndroidView(
        factory = { context ->
            ExoPlayerView(context).apply {
                this.player = player
                this.controllerAutoShow = useController
                this.useController = useController
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier,
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
        }
    )
}