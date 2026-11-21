package com.bedrockmodstudio.ui.model

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.model3d.RideSeats
import com.bedrockmodstudio.core.model3d.RideSpec
import com.bedrockmodstudio.core.model3d.RiderKind
import com.bedrockmodstudio.core.model3d.Seat
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Mount(val creature: RideSeats.Creature, val model: GeoModel, val real: Boolean, val bitmap: Bitmap?, val scale: Double, val spec: RideSpec?)

/**
 * Places pour monter sur une créature, posées à l'œil : la créature est affichée avec un passager assis sur chaque place ;
 * on fait glisser le passager (ou on règle les curseurs) jusqu'à ce qu'il soit bien installé.
 */
@Composable
fun RideScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val files = remember(id) { app.store.files(id) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var creature by remember { mutableStateOf<RideSeats.Creature?>(null) }
    var spec by remember { mutableStateOf(RideSpec()) }
    var sel by remember { mutableIntStateOf(0) }
    var dragMode by remember { mutableStateOf(true) }
    val camera = remember { OrbitCamera() }
    val (ctl, start) = rememberGenerator(id, "Places pour monter")

    val creatures by produceState<List<RideSeats.Creature>?>(null) { value = withContext(Dispatchers.IO) { runCatching { RideSeats.creatures(files) }.getOrDefault(emptyList()) } }
    val mount by produceState<Mount?>(null, creature) {
        val c = creature ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val look = RideSeats.look(files, c)
                val existing = RideSeats.read(files.readText(c.path))
                Mount(c, look.model ?: RideSeats.placeholder(look), look.model != null, if (look.model != null) loadBitmap(files, look.texturePath) else null, look.scale, existing)
            }.getOrNull()
        }
    }
    LaunchedEffect(mount) {
        val m = mount ?: return@LaunchedEffect
        spec = m.spec ?: RideSpec(listOf(RideSeats.defaultSeat(m.model, m.scale)))
        sel = 0
        camera.frame(RideSeats.creatureFaces(m.model, m.scale))
        camera.distance *= 1.4f
    }

    fun updateSeat(change: (Seat) -> Seat) {
        val i = sel.coerceIn(0, (spec.seats.size - 1).coerceAtLeast(0))
        if (spec.seats.isEmpty()) return
        spec = spec.copy(seats = spec.seats.mapIndexed { j, s -> if (j == i) change(s) else s })
    }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.ride_title), subtitle = creature?.id, onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val m = mount
            if (m != null) {
                val faces = remember(m, spec) {
                    RideSeats.creatureFaces(m.model, m.scale) + spec.seats.flatMapIndexed { i, s -> RideSeats.riderFaces(s, i) }
                }
                MeshViewport(
                    faces, m.bitmap, m.model.textureWidth, m.model.textureHeight, camera, lighting = true, lightAngle = 45f,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    faceColor = { f: MeshFace ->
                        when {
                            f.boneIndex < 0 -> if (-1 - f.boneIndex == sel) 0xFFFFD166.toInt() else 0xFF4FC3F7.toInt()
                            m.bitmap == null -> 0xFF8FA3B8.toInt()
                            else -> null
                        }
                    },
                    selectionKey = sel,
                    onDragObject = if (dragMode && spec.seats.isNotEmpty()) ({ d: Vec3 -> updateSeat { it.copy(position = it.position + d * (1.0 / 16)) } }) else null,
                ) { f -> if (f.boneIndex < 0) sel = -1 - f.boneIndex }
            }
            Column(Modifier.fillMaxWidth().weight(if (m != null) 1.1f else 1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m == null) InfoCard(stringResource(R.string.ride_intro))
                SectionTitle(stringResource(R.string.ride_step_creature))
                val list = creatures
                if (list != null && list.isEmpty()) {
                    EmptyState(stringResource(R.string.ride_no_creature))
                    OutlinedButton(onClick = { nav.navigate(Routes.mob(id)) }) { Text(stringResource(R.string.ride_create_creature)) }
                } else if (list != null) ChoiceChips(list, creature, { it?.id?.substringAfter(':') ?: "" }) { creature = it }
                if (m == null) return@Column
                if (!m.real) InfoCard(stringResource(R.string.ride_placeholder), color = BmsColors.Warning)

                SectionTitle(stringResource(R.string.ride_seats))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.seats.forEachIndexed { i, _ -> FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text(stringResource(R.string.ride_seat_n, i + 1)) }) }
                    AssistChip(enabled = spec.seats.size < 8, onClick = {
                        val last = spec.seats.lastOrNull()?.position ?: Vec3(0.0, 1.0, 0.0)
                        spec = spec.copy(seats = spec.seats + Seat(last + Vec3(0.0, 0.0, 0.6))); sel = spec.seats.lastIndex
                    }, label = { Text(stringResource(R.string.ride_add_seat)) }, modifier = Modifier.testTag("ride_add"))
                    AssistChip(enabled = spec.seats.size > 1, onClick = {
                        spec = spec.copy(seats = spec.seats.filterIndexed { j, _ -> j != sel }); sel = 0
                    }, label = { Text(stringResource(R.string.ride_remove_seat), color = BmsColors.Error) })
                }
                HelpText(stringResource(R.string.ride_seats_help))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ride_drag_mode), Modifier.weight(1f))
                    Switch(dragMode, { dragMode = it })
                }
                val seat = spec.seats.getOrNull(sel)
                if (seat != null) {
                    Text(stringResource(R.string.ride_position), style = MaterialTheme.typography.titleSmall)
                    AxisSlider(stringResource(R.string.ride_x), seat.position.x, -4.0..4.0, 0.05, " b") { v -> updateSeat { it.copy(position = it.position.copy(x = v)) } }
                    AxisSlider(stringResource(R.string.ride_y), seat.position.y, -1.0..6.0, 0.05, " b") { v -> updateSeat { it.copy(position = it.position.copy(y = v)) } }
                    AxisSlider(stringResource(R.string.ride_z), seat.position.z, -4.0..4.0, 0.05, " b") { v -> updateSeat { it.copy(position = it.position.copy(z = v)) } }
                    HelpText(stringResource(R.string.ride_units))
                    Text(stringResource(R.string.ride_facing), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.0 to R.string.ride_face_front, 180.0 to R.string.ride_face_back, 90.0 to R.string.ride_face_left, -90.0 to R.string.ride_face_right).forEach { (a, label) ->
                            FilterChip(selected = seat.rotateRiderBy == a, onClick = { updateSeat { it.copy(rotateRiderBy = a) } }, label = { Text(stringResource(label)) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ride_lock), Modifier.weight(1f))
                        Switch(seat.lockRiderRotation != null, { on -> updateSeat { it.copy(lockRiderRotation = if (on) 90.0 else null) } })
                    }
                    seat.lockRiderRotation?.let { l -> AxisSlider(stringResource(R.string.ride_lock_angle), l, 0.0..180.0, 5.0, "°") { v -> updateSeat { it.copy(lockRiderRotation = v) } } }
                }

                SectionTitle(stringResource(R.string.ride_options))
                ChoiceChips(RiderKind.entries, spec.riders, { it.labelFr }) { spec = spec.copy(riders = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.ride_control)); HelpText(stringResource(R.string.ride_control_help)) }
                    Switch(spec.controllable, { spec = spec.copy(controllable = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.ride_pull_in)); HelpText(stringResource(R.string.ride_pull_in_help)) }
                    Switch(spec.pullInEntities, { spec = spec.copy(pullInEntities = it) })
                }
                spec.validate().takeIf { it.isNotEmpty() }?.let { InfoCard(it.joinToString("\n"), color = BmsColors.Warning) }
                InfoCard(stringResource(R.string.ride_approx))
                Button(
                    onClick = {
                        scope.launch(Dispatchers.Main.immediate) {
                            withContext(Dispatchers.IO) { runCatching { RideSeats.apply(files, m.creature, spec) } }
                                .onSuccess(start).onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                        }
                    },
                    enabled = spec.validate().isEmpty() && !ctl.busy,
                    modifier = Modifier.fillMaxWidth().testTag("ride_save"),
                ) { Text(stringResource(R.string.ride_save)) }
                GenerationResult(ctl, stringResource(R.string.ride_test_hint))
            }
        }
    }
}
