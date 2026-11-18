package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.layout.ProfileSummary

/** "Who's watching?": one card per profile, plus Add profile. Locked profiles show a lock. */
@Composable
fun ProfilePickerScreen(
    profiles: List<ProfileSummary>,
    currentId: String?,
    onPick: (ProfileSummary) -> Unit,
    onAdd: () -> Unit,
) {
    val initial = remember { FocusRequester() }
    val focusIndex = profiles.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResource(R.string.whos_watching), style = MaterialTheme.typography.displaySmall)
        // A plain Row (households have a handful of profiles), so the focus target exists right away.
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            profiles.forEachIndexed { index, profile ->
                ProfileCard(
                    label = if (profile.hasPin) "🔒 ${profile.name}" else profile.name,
                    onClick = { onPick(profile) },
                    modifier = if (index == focusIndex) Modifier.focusRequester(initial) else Modifier,
                )
            }
            ProfileCard(label = stringResource(R.string.add_profile), onClick = onAdd)
        }
    }
    LaunchedEffect(profiles.size) { if (profiles.isNotEmpty()) initial.requestFocus() }
}

@Composable
private fun ProfileCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(20.dp)),
        modifier = modifier.size(180.dp, 120.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
