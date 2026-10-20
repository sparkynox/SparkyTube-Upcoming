package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem

/**
 * Everything under the player while a video is open: title, channel, description, up next.
 * The player itself is the existing ExoPlayer view drawn on top of the first 220dp of the screen.
 */
@Composable
fun WatchScreen(nav: NavViewModel, onOpen: (HomeFeedItem) -> Unit, onMore: (HomeFeedItem) -> Unit) {
    val info = nav.watchInfo
    var expanded by remember(nav.watchId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val nearEnd by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, nav.related.size) { if (nearEnd && nav.related.isNotEmpty()) nav.loadMoreRelated() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            state = list,
            // the first 220dp belong to the ExoPlayer view that MainActivity draws above this
            modifier = Modifier.fillMaxSize().padding(top = 220.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        info?.title ?: nav.watchTitle,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    info?.meta?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (info == null) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            if (info?.channelName != null) item(key = "channel") {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(info.channelAvatar, info.channelName, 40)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            info.channelName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        info.subscribers?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (!info?.description.isNullOrBlank()) item(key = "description") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp)).clickable { expanded = !expanded },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        info?.description.orEmpty(),
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (nav.related.isNotEmpty()) item(key = "upnext") {
                Text(
                    "Up next", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
            }
            items(nav.related, key = { it.videoId ?: it.playlistId ?: it.title }) { item ->
                VideoCard(item, onOpen = { onOpen(item) }, onMore = { onMore(item) })
            }
            if (nav.relatedLoading) item(key = "more") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }
}
