/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.ui.player.TrackState
import eu.kanade.tachiyomi.ui.player.VideoTrack
import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitleLanguage
import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitleResult
import eu.kanade.tachiyomi.ui.player.subtitles.OnlineSubtitleUiState
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// ANZ -->
@Composable
fun SubtitlesSheet(
    tracks: ImmutableList<VideoTrack>,
    onSelect: (VideoTrack) -> Unit,
    onAddSubtitle: () -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    onOpenSubtitleDelay: () -> Unit,
    onlineSubtitleState: OnlineSubtitleUiState,
    onPrepareOnlineSearch: () -> Unit,
    onOnlineQueryChange: (String) -> Unit,
    onOnlineSeasonChange: (String) -> Unit,
    onOnlineEpisodeChange: (String) -> Unit,
    onOnlineLanguageSelect: (OnlineSubtitleLanguage) -> Unit,
    onOnlineSearch: () -> Unit,
    onDownloadOnlineSubtitle: (OnlineSubtitleResult) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOnlineTab by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showOnlineTab) {
        if (showOnlineTab) {
            onPrepareOnlineSearch()
        }
    }

    PlayerSheet(onDismissRequest) {
        Column(modifier = modifier.fillMaxWidth()) {
            TrackSheetTitle(
                title = stringResource(MR.strings.pref_player_subtitle),
                actions = {
                    TextButton(onClick = onOpenSubtitleSettings) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        ) {
                            Icon(imageVector = Icons.Default.Palette, contentDescription = null)
                            Text(text = stringResource(MR.strings.player_sheets_track_palette))
                        }
                    }
                    TextButton(onClick = onOpenSubtitleDelay) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        ) {
                            Icon(imageVector = Icons.Default.MoreTime, contentDescription = null)
                            Text(text = stringResource(MR.strings.player_sheets_track_delay))
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                FilterChip(
                    selected = !showOnlineTab,
                    onClick = { showOnlineTab = false },
                    label = { Text(stringResource(AMR.strings.player_sheets_subtitles_tab_tracks)) },
                )
                FilterChip(
                    selected = showOnlineTab,
                    onClick = { showOnlineTab = true },
                    label = { Text(stringResource(AMR.strings.player_sheets_subtitles_tab_online)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            if (!showOnlineTab) {
                AddTrackRow(
                    title = stringResource(MR.strings.player_sheets_add_ext_sub),
                    onClick = onAddSubtitle,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showOnlineTab = true }
                        .height(48.dp)
                        .padding(horizontal = MaterialTheme.padding.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(text = stringResource(AMR.strings.player_sheets_online_subtitles))
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(tracks) { track ->
                        SubtitleTrackRow(
                            track = track,
                            selected = track.selection,
                            onClick = { onSelect(track) },
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .padding(MaterialTheme.padding.medium)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Icon(Icons.Outlined.Info, null)
                            Text(stringResource(MR.strings.player_sheets_subtitles_footer_secondary_sid_no_styles))
                        }
                    }
                }
            } else {
                OnlineSubtitlesContent(
                    state = onlineSubtitleState,
                    onQueryChange = onOnlineQueryChange,
                    onSeasonChange = onOnlineSeasonChange,
                    onEpisodeChange = onOnlineEpisodeChange,
                    onLanguageSelect = onOnlineLanguageSelect,
                    onSearch = onOnlineSearch,
                    onDownload = onDownloadOnlineSubtitle,
                )
            }
        }
    }
}

@Composable
private fun OnlineSubtitlesContent(
    state: OnlineSubtitleUiState,
    onQueryChange: (String) -> Unit,
    onSeasonChange: (String) -> Unit,
    onEpisodeChange: (String) -> Unit,
    onLanguageSelect: (OnlineSubtitleLanguage) -> Unit,
    onSearch: () -> Unit,
    onDownload: (OnlineSubtitleResult) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(AMR.strings.player_online_subtitles_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            OutlinedTextField(
                value = state.season,
                onValueChange = onSeasonChange,
                modifier = Modifier.width(56.dp),
                singleLine = true,
                placeholder = { Text(stringResource(AMR.strings.player_online_subtitles_season_hint)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = state.episode,
                onValueChange = onEpisodeChange,
                modifier = Modifier.width(64.dp),
                singleLine = true,
                placeholder = { Text(stringResource(AMR.strings.player_online_subtitles_episode_hint)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            FilledTonalIconButton(
                onClick = onSearch,
                enabled = !state.isSearching && state.query.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(AMR.strings.player_online_subtitles_search),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            FilterChip(
                selected = state.language == OnlineSubtitleLanguage.ARABIC,
                onClick = { onLanguageSelect(OnlineSubtitleLanguage.ARABIC) },
                label = { Text(stringResource(AMR.strings.player_online_subtitles_lang_ar)) },
            )
            FilterChip(
                selected = state.language == OnlineSubtitleLanguage.ENGLISH,
                onClick = { onLanguageSelect(OnlineSubtitleLanguage.ENGLISH) },
                label = { Text(stringResource(AMR.strings.player_online_subtitles_lang_en)) },
            )
            FilterChip(
                selected = state.language == OnlineSubtitleLanguage.ALL,
                onClick = { onLanguageSelect(OnlineSubtitleLanguage.ALL) },
                label = { Text(stringResource(AMR.strings.player_online_subtitles_lang_all)) },
            )
        }

        state.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            state.isSearching -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.results.isEmpty() && state.hasSearched -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.padding.large),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(AMR.strings.player_online_subtitles_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                ) {
                    items(state.results, key = { it.id }) { item ->
                        val isDownloading = state.downloadingId == item.id
                        OnlineSubtitleResultRow(
                            item = item,
                            isDownloading = isDownloading,
                            enabled = state.downloadingId == null,
                            onClick = { onDownload(item) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineSubtitleResultRow(
    item: OnlineSubtitleResult,
    isDownloading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.releaseName.ifBlank { item.title },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(item.languageLabel)
                add(item.format.uppercase())
                add(item.sourceLabel)
                if (item.downloadCount > 0) {
                    add("${item.downloadCount} dl")
                }
            }.joinToString(" • ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun SubtitleTrackRow(
    track: VideoTrack,
    selected: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = MaterialTheme.padding.small, end = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected > -1,
            onCheckedChange = { _ -> onClick() },
        )
        Text(
            text = getTrackTitle(track),
            fontStyle = if (selected > -1) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (selected > -1) FontWeight.ExtraBold else FontWeight.Normal,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (track is VideoTrack.External && track.state == TrackState.Loading) {
            CircularProgressIndicator(modifier = Modifier.then(Modifier.size(24.dp)))
        } else if (track is VideoTrack.External && track.state == TrackState.Error) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        } else if (selected != -1) {
            Text(
                text = "#${selected + 1}",
                fontStyle = if (selected > -1) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (selected > -1) FontWeight.ExtraBold else FontWeight.Normal,
            )
        }
    }
}
// ANZ <--
