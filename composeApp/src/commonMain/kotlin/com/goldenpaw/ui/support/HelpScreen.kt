package com.goldenpaw.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goldenpaw.data.support.SupportDiagnostics
import com.goldenpaw.data.support.SupportService
import com.goldenpaw.data.support.SupportTopic
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.platform.PlatformKind
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.motionOr
import androidx.compose.animation.core.tween
import com.goldenpaw.ui.designsystem.MotionSpecs
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.collapseTransition
import com.goldenpaw.ui.designsystem.errorShake
import com.goldenpaw.ui.designsystem.expandTransition
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Route
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.koin.compose.koinInject

/**
 * Help & support: answers first (self-serve FAQ), then the shortest path to a human. Every message
 * carries a support id and app diagnostics, so most questions can be answered in one reply.
 */
@Composable
fun HelpScreen() {
    val support: SupportService = koinInject()
    val settings: SettingsRepository = koinInject()
    val auth: CloudAuthRepository = koinInject()
    val platform = LocalPlatform.current
    val actions = LocalAppActions.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var supportId by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf<SupportDiagnostics?>(null) }
    var compose by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        supportId = support.supportId()
        val s = settings.current()
        diagnostics = SupportDiagnostics(
            platform = platform.kind.name.lowercase(),
            supportId = supportId,
            plus = s.isPlus,
            signedIn = auth.session.value != null,
            remindersOk = platform.reminderHealth().allGood,
            locale = androidx.compose.ui.text.intl.Locale.current.toLanguageTag(),
            timezone = TimeZone.currentSystemDefault().id,
        )
    }

    Scaffold(
        topBar = { GpTopBar("Help & support", onBack = actions.back) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SectionHeader("Common questions") }
            items(Faq.forPlatform(platform.kind), key = { it.id }) { entry ->
                FaqCard(entry, onAction = { action ->
                    when (action) {
                        FaqAction.REMINDERS -> actions.navigate(Route.Reminders)
                        FaqAction.MANAGE_SUBSCRIPTION -> subscriptionUrl(platform.kind)?.let(platform::openUrl)
                        FaqAction.CARE_TEAM -> actions.navigate(Route.CareTeam)
                        FaqAction.SETTINGS -> actions.navigate(Route.Settings)
                    }
                })
            }

            item { SectionHeader("Still need help?") }
            item {
                GpCard {
                    Text(
                        "Write to us and we'll reply, usually within two working days. Your message includes your support ID " +
                            "and app details (no pet records), so we can help without asking twice.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (support.canSendInApp) {
                        FilledTonalButton(onClick = { compose = true }, modifier = Modifier.fillMaxWidth()) { Text("Send us a message") }
                    }
                    val d = diagnostics
                    val mail = d?.let { support.emailLink(SupportTopic.OTHER, it) }
                    if (mail != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { platform.openUrl(mail) }, modifier = Modifier.fillMaxWidth()) { Text("Email ${support.email}") }
                    }
                    if (!support.canSendInApp && mail == null) {
                        Text(
                            "Support contact isn't set up in this build.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            subscriptionUrl(platform.kind)?.let { url ->
                item {
                    SupportRow(
                        title = "Manage or cancel Plus",
                        body = if (platform.kind == PlatformKind.IOS) {
                            "Plus is billed by Apple. Cancel or ask for a refund in your App Store subscriptions."
                        } else {
                            "Plus is billed by Google Play. Cancel in your Play subscriptions; refunds are possible there within 48 hours, and after that through us."
                        },
                        action = "Open",
                        onClick = { platform.openUrl(url) },
                    )
                }
            }
            if (support.privacyPolicyUrl.isNotBlank()) {
                item {
                    SupportRow(
                        title = "Privacy policy",
                        body = "What GoldenPaw stores, where, and how to delete it.",
                        action = "Read",
                        onClick = { platform.openUrl(support.privacyPolicyUrl) },
                    )
                }
            }
            item {
                SupportRow(
                    title = "Support ID",
                    body = supportId.ifBlank { "…" },
                    action = "Copy",
                    monospace = true,
                    onClick = {
                        clipboard.setText(AnnotatedString(supportId))
                        scope.launch { snackbar.showSnackbar("Support ID copied") }
                    },
                )
            }
        }
    }

    val d = diagnostics
    if (compose && d != null) {
        ComposeMessageDialog(
            diagnostics = d,
            onDismiss = { compose = false },
            onSend = { topic, message, email, done ->
                scope.launch {
                    support.send(topic, message, email, d)
                        .onSuccess { id ->
                            compose = false
                            snackbar.showSnackbar("Thanks! Message #$id received. We'll reply ${if (email.isNullOrBlank()) "here in a future update" else "by email"}.")
                        }
                        .onFailure { done(it.message ?: "Couldn't send. Please try again.") }
                }
            },
        )
    }
}

/** Store page where the owner manages (and on iOS, refunds) their subscription. Null on Desktop. */
internal fun subscriptionUrl(kind: PlatformKind): String? = when (kind) {
    PlatformKind.ANDROID -> "https://play.google.com/store/account/subscriptions?package=com.goldenpaw.app"
    PlatformKind.IOS -> "https://apps.apple.com/account/subscriptions"
    PlatformKind.DESKTOP -> null
}

@Composable
private fun FaqCard(entry: FaqEntry, onAction: (FaqAction) -> Unit) {
    var open by rememberSaveable(entry.id) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, animationSpec = motionOr(tween(Motion.SHORT4, easing = Motion.EmphasizedDecelerate)), label = "faqChevron")
    GpCard(onClick = { open = !open }, contentPadding = PaddingValues(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        ) {
            Text(entry.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            visible = open,
            enter = expandTransition(MotionSpecs.ExpandPreset.Inline),
            exit = collapseTransition(MotionSpecs.ExpandPreset.Inline),
        ) {
            Column(Modifier.padding(top = 8.dp)) {
                Text(entry.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val action = entry.action
                if (action != null) {
                    TextButton(onClick = { onAction(action) }, contentPadding = PaddingValues(0.dp)) { Text(entry.actionLabel.orEmpty()) }
                }
            }
        }
    }
}

@Composable
private fun SupportRow(title: String, body: String, action: String, onClick: () -> Unit, monospace: Boolean = false) {
    GpCard(contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun ComposeMessageDialog(
    diagnostics: SupportDiagnostics,
    onDismiss: () -> Unit,
    onSend: (SupportTopic, String, String?, (String) -> Unit) -> Unit,
) {
    var topic by rememberSaveable { mutableStateOf(SupportTopic.OTHER) }
    var message by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send us a message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What's it about?", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = SupportTopic.entries,
                    selected = { it == topic },
                    onToggle = { topic = it },
                    label = { it.label },
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(SupportService.MAX_LENGTH); error = null },
                    label = { Text("Your message") },
                    minLines = 4,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().errorShake(error),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim(); error = null },
                    label = { Text("Email for our reply (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showDetails = !showDetails }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showDetails) "Hide what we'll include" else "See what we'll include")
                }
                AnimatedVisibility(
                    showDetails,
                    enter = expandTransition(MotionSpecs.ExpandPreset.Inline),
                    exit = collapseTransition(MotionSpecs.ExpandPreset.Inline),
                ) {
                    Text(
                        diagnostics.asText(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && message.trim().length >= SupportService.MIN_LENGTH,
                onClick = {
                    sending = true
                    onSend(topic, message, email.ifBlank { null }) { failure ->
                        sending = false
                        error = failure
                    }
                },
            ) {
                if (sending) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp) else Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}
