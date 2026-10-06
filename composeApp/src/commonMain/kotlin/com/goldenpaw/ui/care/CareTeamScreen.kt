package com.goldenpaw.ui.care

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.core.Fmt
import com.goldenpaw.core.at
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.model.CareActivity
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.domain.model.SyncStatus
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.LocalAppClock
import com.goldenpaw.ui.common.rememberToday
import com.goldenpaw.ui.designsystem.CaregiverAvatar
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalCaregiverPalette
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.staggerIn
import com.goldenpaw.ui.designsystem.rememberStaggerState
import com.goldenpaw.ui.navigation.LocalAppActions
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CareTeamScreen() {
    val vm: CareTeamViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    val stagger = rememberStaggerState()
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var inviteDialog by rememberSaveable { mutableStateOf(false) }
    var joinDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ui.message, ui.error) {
        val text = ui.error ?: ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.clearMessage()
    }

    Scaffold(
        topBar = { GpTopBar("Care team", onBack = actions.back) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val team = state.team
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "intro") {
                Text(
                    "Everyone on the team sees the same doses, check-ins and journal, and every entry shows who logged it. " +
                        "If a dose was already given, GoldenPaw warns before anyone gives it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (team != null) {
                item(key = "members") {
                    Column(Modifier.staggerIn(stagger, "members", 0)) {
                        SectionHeader(team.household.name)
                        GpCard {
                            team.activeMembers.forEachIndexed { i, m ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                MemberRow(
                                    member = m,
                                    isMe = m.id == team.me?.id,
                                    canManage = state.canManage && m.role != CaregiverRole.OWNER,
                                    sharingOn = state.sharingOn,
                                    onSwitch = { vm.switchTo(m) },
                                    onUpdate = vm::updateMember,
                                    onRemove = { vm.remove(m) },
                                )
                            }
                            if (state.canManage) {
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (state.sharingOn) {
                                        Button(onClick = { inviteDialog = true }, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Outlined.Share, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Invite")
                                        }
                                    }
                                    OutlinedButton(onClick = { addDialog = true }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("On this device")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "sharing") {
                Column(Modifier.staggerIn(stagger, "sharing", 1)) {
                    SectionHeader("Sharing across phones")
                    SharingCard(state, ui, vm, onJoin = { joinDialog = true })
                }
            }
            if (state.activity.isNotEmpty()) {
                item(key = "logHeader") { SectionHeader("Care log") }
                items(state.activity, key = { it.id }) { a ->
                    ActivityRow(a, modifier = Modifier.animateItem())
                }
            }
        }
    }

    if (addDialog) {
        AddLocalPersonDialog(onDismiss = { addDialog = false }, onAdd = { name, role -> vm.addLocalPerson(name, role) })
    }
    if (inviteDialog) {
        InviteDialog(
            busy = ui.busy,
            invite = ui.invite,
            onCreate = vm::createInvite,
            onDismiss = { inviteDialog = false; vm.clearInvite() },
        )
    }
    if (joinDialog) {
        JoinDialog(busy = ui.busy, onJoin = { vm.acceptInvite(it); joinDialog = false }, onDismiss = { joinDialog = false })
    }
}

@Composable
private fun MemberRow(
    member: Caregiver,
    isMe: Boolean,
    canManage: Boolean,
    sharingOn: Boolean,
    onSwitch: () -> Unit,
    onUpdate: (Caregiver) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var endDate by remember { mutableStateOf(false) }
    val clock = LocalAppClock.current
    val expired = !member.isActiveAt(clock.now())
    Row(verticalAlignment = Alignment.CenterVertically) {
        CaregiverAvatar(member, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.displayName, style = MaterialTheme.typography.titleSmall)
                if (isMe) {
                    Spacer(Modifier.width(6.dp))
                    Pill("Logging now", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            val bits = buildList {
                add(member.role.label)
                if (member.userId == null) add(if (sharingOn) "this device" else "on this device")
                member.accessUntil?.let { add((if (expired) "access ended " else "until ") + Fmt.dayMonth(it.toLocalDate(clock.zone()))) }
                if (member.status == MemberStatus.INVITED) add("invited")
            }
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${member.displayName}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!isMe && member.userId == null && !expired) {
                    DropdownMenuItem(text = { Text("Switch to ${member.displayName}") }, onClick = { menu = false; onSwitch() })
                }
                if (canManage) {
                    CaregiverRole.invitable.filter { it != member.role }.forEach { role ->
                        DropdownMenuItem(text = { Text("Make ${role.label.lowercase()}") }, onClick = { menu = false; onUpdate(member.copy(role = role)) })
                    }
                    DropdownMenuItem(text = { Text("Set access end date") }, onClick = { menu = false; endDate = true })
                    if (member.accessUntil != null) {
                        DropdownMenuItem(text = { Text("Remove end date") }, onClick = { menu = false; onUpdate(member.copy(accessUntil = null)) })
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from team", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
    if (endDate) {
        com.goldenpaw.ui.common.GpDatePickerDialog(
            initial = null,
            onDismiss = { endDate = false },
            onPicked = { d -> onUpdate(member.copy(accessUntil = d.plusDays(1).at(LocalTime(0, 0), clock.zone()))) },
            allowFuture = true,
            minDate = clock.now().toLocalDate(clock.zone()),
        )
    }
}

@Composable
private fun SharingCard(state: CareTeamState, ui: CareTeamUi, vm: CareTeamViewModel, onJoin: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    GpCard {
        val stage = when {
            !state.cloudAvailable -> 0
            state.session == null -> 1
            !state.sharingOn -> 2
            else -> 3
        }
        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn(tween(if (reduceMotion) 0 else 220)) togetherWith fadeOut(tween(if (reduceMotion) 0 else 120)) },
            label = "sharingStage",
        ) { s ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    0 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudOff, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Sharing isn't set up in this build", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "You can still add people who log on this device, like a family tablet. " +
                                "To sync between phones, the app needs its cloud keys (see the README).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    1 -> SignInForm(ui, vm)
                    2 -> {
                        Text("Signed in as ${state.session?.email}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Share these pets with your team", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "This uploads your pets and logs so invited people can see and add to them. You stay the owner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.canManage) {
                            Button(onClick = vm::enableSharing, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                                if (ui.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Turn on sharing")
                            }
                        }
                        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("I have an invite code") }
                        TextButton(onClick = vm::signOut) { Text("Sign out") }
                    }
                    else -> {
                        val sync = state.sync
                        val clock = LocalAppClock.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (sync.status == SyncStatus.ERROR) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
                                contentDescription = null,
                                tint = if (sync.status == SyncStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (sync.status) {
                                        SyncStatus.SYNCING -> "Syncing…"
                                        SyncStatus.ERROR -> "Couldn't sync"
                                        else -> "Shared and in sync"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    sync.message ?: sync.lastSyncedAt?.let { "Last synced ${Fmt.ago(it, clock.now(), clock.zone())}" }
                                        ?: "Signed in as ${state.session?.email}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (sync.status == SyncStatus.SYNCING || ui.busy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = vm::syncNow) { Icon(Icons.Outlined.Sync, contentDescription = "Sync now") }
                            }
                        }
                        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Join another team with a code") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInForm(ui: CareTeamUi, vm: CareTeamViewModel) {
    Text("Sign in to share", style = MaterialTheme.typography.titleSmall)
    Text(
        "We'll email you a one-time code. No password. Your account is only used to sync your care team.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = ui.email,
        onValueChange = vm::setEmail,
        label = { Text("Email") },
        singleLine = true,
        enabled = !ui.codeSent,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    AnimatedVisibility(ui.codeSent) {
        OutlinedTextField(
            value = ui.otp,
            onValueChange = vm::setOtp,
            label = { Text("Code from the email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = { if (ui.codeSent) vm.verifyCode() else vm.requestCode() },
        enabled = !ui.busy && (if (ui.codeSent) ui.otp.length >= 6 else ui.email.contains('@')),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (ui.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(if (ui.codeSent) "Sign in" else "Email me a code")
    }
    if (ui.codeSent) TextButton(onClick = vm::changeEmail) { Text("Use a different email") }
}

@Composable
private fun ActivityRow(a: CareActivity, modifier: Modifier = Modifier) {
    val palette = LocalCaregiverPalette.current
    val clock = LocalAppClock.current
    val today = rememberToday()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(a.kind.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyMedium)
            val when_ = "${Formats.relativeDay(a.at.toLocalDate(clock.zone()), today)} · ${Formats.time(a.at)}"
            Text(
                listOf(a.detail, when_).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (a.by.name.isNotBlank()) {
            Text(
                a.by.name,
                style = MaterialTheme.typography.labelMedium,
                color = palette.forIndex(a.by.name.hashCode()),
            )
        }
    }
}

@Composable
private fun AddLocalPersonDialog(onDismiss: () -> Unit, onAdd: (String, CaregiverRole) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(CaregiverRole.FAMILY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add someone on this device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "For people who share this phone or tablet. They can switch to themselves from Today, so every dose shows who gave it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                RolePicker(role) { role = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name, role); onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RolePicker(role: CaregiverRole, onRole: (CaregiverRole) -> Unit) {
    ChoiceChips(options = CaregiverRole.invitable, selected = { it == role }, onToggle = onRole, label = { it.label })
    Text(role.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InviteDialog(
    busy: Boolean,
    invite: com.goldenpaw.domain.model.Invite?,
    onCreate: (CaregiverRole, kotlinx.datetime.Instant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatform.current
    val clipboard = LocalClipboardManager.current
    val clock = LocalAppClock.current
    var role by rememberSaveable { mutableStateOf(CaregiverRole.FAMILY) }
    var until by remember { mutableStateOf<kotlinx.datetime.LocalDate?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (invite == null) "Invite to the care team" else "Share this code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (invite == null) {
                    RolePicker(role) { role = it }
                    if (role == CaregiverRole.SITTER) {
                        DateField(
                            label = "Access ends (optional)",
                            date = until,
                            onDate = { until = it },
                            placeholder = "No end date",
                            allowFuture = true,
                            minDate = clock.now().toLocalDate(clock.zone()),
                        )
                    }
                } else {
                    Text(
                        invite.code,
                        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        "They install GoldenPaw, open Care team and tap \"I have an invite code\". " +
                            "The code works once and expires ${Fmt.dayMonth(invite.expiresAt.toLocalDate(clock.zone()))}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(invite.code)) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = {
                                platform.share(
                                    "Join our pet care team on GoldenPaw as ${invite.role.label.lowercase()}. Invite code: ${invite.code}",
                                    subject = "GoldenPaw invite",
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (invite == null) {
                TextButton(enabled = !busy, onClick = {
                    onCreate(role, until?.plusDays(1)?.at(LocalTime(0, 0), clock.zone()))
                }) { Text(if (busy) "Creating…" else "Create code") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { if (invite == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JoinDialog(busy: Boolean, onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a care team") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the code you were sent. Pets on that team will appear alongside yours.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(9) },
                    label = { Text("Invite code") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = !busy && code.length >= 8, onClick = { onJoin(code) }) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
