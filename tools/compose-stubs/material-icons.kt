package androidx.compose.material.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A representative slice of material-icons-extended. Icons are generated
 * objects in the real artifact, so a name that does not exist there must not
 * resolve here either — do not add one without checking it is real.
 */
object Icons {
    object Filled {
        val Add = icon("Filled.Add")
        val ArrowBack = icon("Filled.ArrowBack")
        val Check = icon("Filled.Check")
        val Close = icon("Filled.Close")
        val Delete = icon("Filled.Delete")
        val Edit = icon("Filled.Edit")
        val Home = icon("Filled.Home")
        val Info = icon("Filled.Info")
        val List = icon("Filled.List")
        val MoreVert = icon("Filled.MoreVert")
        val Notifications = icon("Filled.Notifications")
        val Search = icon("Filled.Search")
        val Settings = icon("Filled.Settings")
        val Share = icon("Filled.Share")
        val Warning = icon("Filled.Warning")
    }

    object Outlined {
        val Add = icon("Outlined.Add")
        val Delete = icon("Outlined.Delete")
        val Edit = icon("Outlined.Edit")
        val Info = icon("Outlined.Info")
        val Settings = icon("Outlined.Settings")
        val Warning = icon("Outlined.Warning")
    }

    object AutoMirrored {
        object Filled {
            val ArrowBack = icon("AutoMirrored.Filled.ArrowBack")
            val List = icon("AutoMirrored.Filled.List")
        }
    }

    val Default = Filled
}

private fun icon(name: String): ImageVector = ImageVectorFactory.of(name)

internal object ImageVectorFactory {
    fun of(name: String): ImageVector = ImageVector(name)
}
