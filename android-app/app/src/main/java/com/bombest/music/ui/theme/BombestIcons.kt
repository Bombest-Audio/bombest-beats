package com.bombest.music.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

object BombestIcons {
    val Play: ImageVector
        get() = materialIcon(name = "Filled.PlayArrow") {
            materialPath {
                moveTo(8.0f, 5.0f)
                verticalLineToRelative(14.0f)
                lineToRelative(11.0f, -7.0f)
                close()
            }
        }

    val Pause: ImageVector
        get() = materialIcon(name = "Filled.Pause") {
            materialPath {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(5.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }

    val SkipNext: ImageVector
        get() = materialIcon(name = "Filled.SkipNext") {
            materialPath {
                moveTo(6.0f, 18.0f)
                lineToRelative(8.5f, -6.0f)
                lineTo(6.0f, 6.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(16.0f, 6.0f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
        }

    val SkipPrevious: ImageVector
        get() = materialIcon(name = "Filled.SkipPrevious") {
            materialPath {
                moveTo(6.0f, 6.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(12.0f)
                horizontalLineTo(6.0f)
                close()
                moveTo(9.5f, 12.0f)
                lineToRelative(8.5f, 6.0f)
                verticalLineTo(6.0f)
                lineToRelative(-8.5f, 6.0f)
                close()
            }
        }

    val KeyboardArrowDown: ImageVector
        get() = materialIcon(name = "Filled.KeyboardArrowDown") {
            materialPath {
                moveTo(7.41f, 8.59f)
                lineTo(12.0f, 13.17f)
                lineToRelative(4.59f, -4.58f)
                lineTo(18.0f, 10.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(-6.0f, -6.0f)
                lineToRelative(1.41f, -1.41f)
                close()
            }
        }

    val Shuffle: ImageVector
        get() = materialIcon(name = "Filled.Shuffle") {
            materialPath {
                moveTo(10.59f, 9.17f)
                lineTo(5.41f, 4.0f)
                lineTo(4.0f, 5.41f)
                lineToRelative(5.17f, 5.17f)
                lineToRelative(1.42f, -1.41f)
                close()
                moveTo(14.5f, 4.0f)
                lineToRelative(2.04f, 2.04f)
                lineTo(4.0f, 18.59f)
                lineTo(5.41f, 20.0f)
                lineTo(17.96f, 7.46f)
                lineTo(20.0f, 9.5f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-5.5f)
                close()
                moveTo(16.54f, 13.71f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(17.96f, 17.96f)
                lineTo(15.92f, 20.0f)
                horizontalLineTo(20.0f)
                verticalLineToRelative(-4.08f)
                lineToRelative(-2.04f, 2.04f)
                lineToRelative(-1.42f, -4.25f)
                close()
            }
        }

    val Repeat: ImageVector
        get() = materialIcon(name = "Filled.Repeat") {
            materialPath {
                moveTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(3.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.0f)
                close()
                moveTo(17.0f, 17.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-3.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                close()
            }
        }

    val RepeatOne: ImageVector
        get() = materialIcon(name = "Filled.RepeatOne") {
            materialPath {
                moveTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(3.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.0f)
                close()
                moveTo(17.0f, 17.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-3.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                close()
                moveTo(13.0f, 15.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(-2.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(4.0f)
                horizontalLineTo(13.0f)
                close()
            }
        }
}
