package com.geeksville.mesh.model

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.RemoteException
import android.view.Menu
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.*
import com.geeksville.mesh.ChannelProtos.ChannelSettings
import com.geeksville.mesh.ClientOnlyProtos.DeviceProfile
import com.geeksville.mesh.ConfigProtos.Config
import com.geeksville.mesh.ModuleConfigProtos.ModuleConfig
import com.geeksville.mesh.database.MeshLogRepository
import com.geeksville.mesh.database.QuickChatActionRepository
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.database.entity.MeshLog
import com.geeksville.mesh.database.entity.QuickChatAction
import com.geeksville.mesh.LocalOnlyProtos.LocalConfig
import com.geeksville.mesh.LocalOnlyProtos.LocalModuleConfig
import com.geeksville.mesh.MeshProtos.User
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.repository.datastore.RadioConfigRepository
import com.geeksville.mesh.repository.radio.RadioInterfaceService
import com.geeksville.mesh.service.MeshService
import com.geeksville.mesh.ui.ConfigRoute
import com.geeksville.mesh.ui.ResponseState
import com.geeksville.mesh.util.positionToMeter
import com.google.protobuf.MessageLite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/// Given a human name, strip out the first letter of the first three words and return that as the initials for
/// that user. If the original name is only one word, strip vowels from the original name and if the result is
/// 3 or more characters, use the first three characters. If not, just take the first 3 characters of the
/// original name.
fun getInitials(nameIn: String): String {
    val nchars = 4
    val minchars = 2
    val name = nameIn.trim()
    val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }

    val initials = when (words.size) {
        in 0 until minchars -> {
            val nm = if (name.isNotEmpty())
                name.first() + name.drop(1).filterNot { c -> c.lowercase() in "aeiou" }
            else
                ""
            if (nm.length >= nchars) nm else name
        }
        else -> words.map { it.first() }.joinToString("")
    }
    return initials.take(nchars)
}

/**
 * Data class that represents the current RadioConfig state.
 */
data class RadioConfigState(
    val route: String = "",
    val userConfig: User = User.getDefaultInstance(),
    val channelList: List<ChannelSettings> = emptyList(),
    val radioConfig: Config = Config.getDefaultInstance(),
    val moduleConfig: ModuleConfig = ModuleConfig.getDefaultInstance(),
    val ringtone: String = "",
    val cannedMessageMessages: String = "",
    val responseState: ResponseState<Boolean> = ResponseState.Empty,
)

@HiltViewModel
class UIViewModel @Inject constructor(
    private val app: Application,
    private val radioConfigRepository: RadioConfigRepository,
    private val radioInterfaceService: RadioInterfaceService,
    private val meshLogRepository: MeshLogRepository,
    private val packetRepository: PacketRepository,
    private val quickChatActionRepository: QuickChatActionRepository,
    private val preferences: SharedPreferences
) : ViewModel(), Logging {

    var actionBarMenu: Menu? = null
    var meshService: IMeshService? = null
    val nodeDB = NodeDB(this)

    val bondedAddress get() = radioInterfaceService.getBondedDeviceAddress()
    val selectedBluetooth get() = radioInterfaceService.getDeviceAddress()?.getOrNull(0) == 'x'

    private val _meshLog = MutableStateFlow<List<MeshLog>>(emptyList())
    val meshLog: StateFlow<List<MeshLog>> = _meshLog

    private val _packets = MutableStateFlow<List<Packet>>(emptyList())
    val packets: StateFlow<List<Packet>> = _packets

    private val _localConfig = MutableStateFlow<LocalConfig>(LocalConfig.getDefaultInstance())
    val localConfig: StateFlow<LocalConfig> = _localConfig
    val config get() = _localConfig.value

    private val _moduleConfig = MutableStateFlow<LocalModuleConfig>(LocalModuleConfig.getDefaultInstance())
    val moduleConfig: StateFlow<LocalModuleConfig> = _moduleConfig
    val module get() = _moduleConfig.value

    private val _channels = MutableStateFlow(ChannelSet())
    val channels: StateFlow<ChannelSet> = _channels

    private val _quickChatActions = MutableStateFlow<List<QuickChatAction>>(emptyList())
    val quickChatActions: StateFlow<List<QuickChatAction>> = _quickChatActions

    private val _ourNodeInfo = MutableStateFlow<NodeInfo?>(null)
    val ourNodeInfo: StateFlow<NodeInfo?> = _ourNodeInfo

    private val requestId = MutableStateFlow<Int?>(null)
    private val _radioConfigState = MutableStateFlow(RadioConfigState())
    val radioConfigState: StateFlow<RadioConfigState> = _radioConfigState

    init {
        radioConfigRepository.nodeInfoFlow().onEach(nodeDB::setNodes)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            meshLogRepository.getAllLogs().collect { logs ->
                _meshLog.value = logs
            }
        }
        viewModelScope.launch {
            packetRepository.getAllPackets().collect { packets ->
                _packets.value = packets
            }
        }
        radioConfigRepository.localConfigFlow.onEach { config ->
            _localConfig.value = config
        }.launchIn(viewModelScope)
        radioConfigRepository.moduleConfigFlow.onEach { config ->
            _moduleConfig.value = config
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            quickChatActionRepository.getAllActions().collect { actions ->
                _quickChatActions.value = actions
            }
        }
        radioConfigRepository.channelSetFlow.onEach { channelSet ->
            _channels.value = ChannelSet(channelSet)
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            combine(meshLogRepository.getAllLogs(9), requestId) { list, id ->
                list.takeIf { id != null }?.firstOrNull { it.meshPacket?.decoded?.requestId == id }
            }.collect(::processPacketResponse)
        }

        debug("ViewModel created")
    }

    private val contactKey: MutableStateFlow<String> = MutableStateFlow(DataPacket.ID_BROADCAST)
    fun setContactKey(contact: String) {
        contactKey.value = contact
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: LiveData<List<Packet>> = contactKey.flatMapLatest { contactKey ->
        packetRepository.getMessagesFrom(contactKey)
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val contacts: LiveData<Map<String, Packet>> = _packets.mapLatest { list ->
        list.filter { it.port_num == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE }
            .associateBy { packet -> packet.contact_key }
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val waypoints: LiveData<Map<Int, Packet>> = _packets.mapLatest { list ->
        list.filter { it.port_num == Portnums.PortNum.WAYPOINT_APP_VALUE }
            .associateBy { packet -> packet.data.waypoint!!.id }
            .filterValues { it.data.waypoint!!.expire > System.currentTimeMillis() / 1000 }
    }.asLiveData()

    private val _destNode = MutableStateFlow<NodeInfo?>(null)
    val destNode: StateFlow<NodeInfo?> get() = if (_destNode.value != null) _destNode else _ourNodeInfo

    /**
     * Sets the destination [NodeInfo] used in Radio Configuration.
     * @param node Destination [NodeInfo] (or null for our local NodeInfo).
     */
    fun setDestNode(node: NodeInfo?) {
        _destNode.value = node
    }

    fun generatePacketId(): Int? {
        return try {
            meshService?.packetId
        } catch (ex: RemoteException) {
            errormsg("RemoteException: ${ex.message}")
            return null
        }
    }

    fun sendMessage(str: String, contactKey: String = "0${DataPacket.ID_BROADCAST}") {
        // contactKey: unique contact key filter (channel)+(nodeId)
        val channel = contactKey[0].digitToIntOrNull()
        val dest = if (channel != null) contactKey.substring(1) else contactKey

        val p = DataPacket(dest, channel ?: 0, str)
        sendDataPacket(p)
    }

    fun sendWaypoint(wpt: MeshProtos.Waypoint, contactKey: String = "0${DataPacket.ID_BROADCAST}") {
        // contactKey: unique contact key filter (channel)+(nodeId)
        val channel = contactKey[0].digitToIntOrNull()
        val dest = if (channel != null) contactKey.substring(1) else contactKey

        val p = DataPacket(dest, channel ?: 0, wpt)
        if (wpt.id != 0) sendDataPacket(p)
    }

    private fun sendDataPacket(p: DataPacket) {
        try {
            meshService?.send(p)
        } catch (ex: RemoteException) {
            errormsg("Send DataPacket error: ${ex.message}")
        }
    }

    private fun request(
        destNum: Int,
        requestAction: suspend (IMeshService, Int, Int) -> Unit,
        errorMessage: String,
    ) = viewModelScope.launch {
        meshService?.let { service ->
            val packetId = service.packetId
            try {
                requestAction(service, packetId, destNum)
                requestId.value = packetId
            } catch (ex: RemoteException) {
                errormsg("$errorMessage: ${ex.message}")
            }
        }
    }

    fun getOwner(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRemoteOwner(packetId, dest) },
        "Request getOwner error"
    )

    fun getChannel(destNum: Int, index: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRemoteChannel(packetId, dest, index) },
        "Request getChannel error"
    )

    fun getConfig(destNum: Int, configType: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRemoteConfig(packetId, dest, configType) },
        "Request getConfig error",
    )

    fun getModuleConfig(destNum: Int, configType: Int) = request(
        destNum,
        { service, packetId, dest -> service.getModuleConfig(packetId, dest, configType) },
        "Request getModuleConfig error",
    )

    fun setRingtone(destNum: Int, ringtone: String) {
        _radioConfigState.update { it.copy(ringtone = ringtone) }
        meshService?.setRingtone(destNum, ringtone)
    }

    fun getRingtone(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRingtone(packetId, dest) },
        "Request getRingtone error"
    )

    fun setCannedMessages(destNum: Int, messages: String) {
        _radioConfigState.update { it.copy(cannedMessageMessages = messages) }
        meshService?.setCannedMessages(destNum, messages)
    }

    fun getCannedMessages(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.getCannedMessages(packetId, dest) },
        "Request getCannedMessages error"
    )

    fun requestTraceroute(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestTraceroute(packetId, dest) },
        "Request traceroute error"
    )

    fun requestShutdown(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestShutdown(packetId, dest) },
        "Request shutdown error"
    )

    fun requestReboot(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestReboot(packetId, dest) },
        "Request reboot error"
    )

    fun requestFactoryReset(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestFactoryReset(packetId, dest) },
        "Request factory reset error"
    )

    fun requestNodedbReset(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestNodedbReset(packetId, dest) },
        "Request NodeDB reset error"
    )

    fun requestPosition(destNum: Int, position: Position = Position(0.0, 0.0, 0)) {
        try {
            meshService?.requestPosition(destNum, position)
        } catch (ex: RemoteException) {
            errormsg("Request position error: ${ex.message}")
        }
    }

    fun deleteAllLogs() = viewModelScope.launch(Dispatchers.IO) {
        meshLogRepository.deleteAll()
    }

    fun deleteAllMessages() = viewModelScope.launch(Dispatchers.IO) {
        packetRepository.deleteAllMessages()
    }

    fun deleteMessages(uuidList: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        packetRepository.deleteMessages(uuidList)
    }

    fun deleteWaypoint(id: Int) = viewModelScope.launch(Dispatchers.IO) {
        packetRepository.deleteWaypoint(id)
    }

    companion object {
        fun getPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences("ui-prefs", Context.MODE_PRIVATE)
    }

    /// Connection state to our radio device
    private val _connectionState = MutableLiveData(MeshService.ConnectionState.DISCONNECTED)
    val connectionState: LiveData<MeshService.ConnectionState> get() = _connectionState

    fun isConnected() = _connectionState.value != MeshService.ConnectionState.DISCONNECTED

    fun setConnectionState(connectionState: MeshService.ConnectionState) {
        _connectionState.value = connectionState
    }

    private val _requestChannelUrl = MutableLiveData<Uri?>(null)
    val requestChannelUrl: LiveData<Uri?> get() = _requestChannelUrl

    fun setRequestChannelUrl(channelUrl: Uri) {
        _requestChannelUrl.value = channelUrl
    }

    /**
     * Called immediately after activity observes requestChannelUrl
     */
    fun clearRequestChannelUrl() {
        _requestChannelUrl.value = null
    }

    private val _snackbarText = MutableLiveData<Any?>(null)
    val snackbarText: LiveData<Any?> get() = _snackbarText

    fun showSnackbar(resString: Any) {
        _snackbarText.value = resString
    }

    /**
     * Called immediately after activity observes [snackbarText]
     */
    fun clearSnackbarText() {
        _snackbarText.value = null
    }

    var txEnabled: Boolean
        get() = config.lora.txEnabled
        set(value) {
            updateLoraConfig { it.copy { txEnabled = value } }
        }

    var region: Config.LoRaConfig.RegionCode
        get() = config.lora.region
        set(value) {
            updateLoraConfig { it.copy { region = value } }
        }

    var ignoreIncomingList: MutableList<Int>
        get() = config.lora.ignoreIncomingList
        set(value) = updateLoraConfig {
            it.copy {
                ignoreIncoming.clear()
                ignoreIncoming.addAll(value)
            }
        }

    // managed mode disables all access to configuration
    val isManaged: Boolean get() = config.device.isManaged

    /// hardware info about our local device (can be null)
    private val _myNodeInfo = MutableLiveData<MyNodeInfo?>()
    val myNodeInfo: LiveData<MyNodeInfo?> get() = _myNodeInfo
    val myNodeNum get() = _myNodeInfo.value?.myNodeNum
    val maxChannels = myNodeInfo.value?.maxChannels ?: 8

    fun setMyNodeInfo(info: MyNodeInfo?) {
        _myNodeInfo.value = info
    }

    override fun onCleared() {
        super.onCleared()
        debug("ViewModel cleared")
    }

    /// Pull our latest node db from the device
    fun updateNodesFromDevice() {
        meshService?.let { service ->
            // Update our nodeinfos based on data from the device
            val nodes = service.nodes.associateBy { it.user?.id!! }
            nodeDB.setNodes(nodes)

            try {
                // Pull down our real node ID - This must be done AFTER reading the nodedb because we need the DB to find our nodeinof object
                val myId = service.myId
                nodeDB.setMyId(myId)
                _ourNodeInfo.value = nodes[myId]
            } catch (ex: Exception) {
                warn("Ignoring failure to get myId, service is probably just uninited... ${ex.message}")
            }
        }
    }

    private inline fun updateLoraConfig(crossinline body: (Config.LoRaConfig) -> Config.LoRaConfig) {
        val data = body(config.lora)
        setConfig(config { lora = data })
    }

    // Set the radio config (also updates our saved copy in preferences)
    fun setConfig(config: Config) {
        meshService?.setConfig(config.toByteArray())
    }

    fun setRemoteConfig(destNum: Int, config: Config) {
        _radioConfigState.update { it.copy(radioConfig = config) }
        meshService?.setRemoteConfig(destNum, config.toByteArray())
    }

    fun setModuleConfig(destNum: Int, config: ModuleConfig) {
        _radioConfigState.update { it.copy(moduleConfig = config) }
        meshService?.setModuleConfig(destNum, config.toByteArray())
    }

    fun setModuleConfig(config: ModuleConfig) {
        setModuleConfig(myNodeNum ?: return, config)
    }

    /**
     * Updates channels to match the [new] list. Only channels with changes are updated.
     *
     * @param destNum Destination node number.
     * @param old The current [ChannelSettings] list.
     * @param new The updated [ChannelSettings] list.
     */
    fun updateChannels(
        destNum: Int,
        old: List<ChannelSettings>,
        new: List<ChannelSettings>,
    ) {
        buildList {
            for (i in 0..maxOf(old.lastIndex, new.lastIndex)) {
                if (old.getOrNull(i) != new.getOrNull(i)) add(channel {
                    role = when (i) {
                        0 -> ChannelProtos.Channel.Role.PRIMARY
                        in 1..new.lastIndex -> ChannelProtos.Channel.Role.SECONDARY
                        else -> ChannelProtos.Channel.Role.DISABLED
                    }
                    index = i
                    settings = new.getOrNull(i) ?: channelSettings { }
                })
            }
        }.forEach { setRemoteChannel(destNum, it) }

        if (destNum == myNodeNum) viewModelScope.launch {
            radioConfigRepository.replaceAllSettings(new)
        }
    }

    private fun updateChannels(
        old: List<ChannelSettings>,
        new: List<ChannelSettings>
    ) {
        updateChannels(myNodeNum ?: return, old, new)
    }

    /**
     * Convert the [channels] array to and from [ChannelSet]
     */
    private var _channelSet: AppOnlyProtos.ChannelSet
        get() = channels.value.protobuf
        set(value) {
            updateChannels(channelSet.settingsList, value.settingsList)

            val newConfig = config { lora = value.loraConfig }
            if (config.lora != newConfig.lora) setConfig(newConfig)
        }
    val channelSet get() = _channelSet

    /// Set the radio config (also updates our saved copy in preferences)
    fun setChannels(channelSet: ChannelSet) {
        this._channelSet = channelSet.protobuf
    }

    private fun setRemoteChannel(destNum: Int, channel: ChannelProtos.Channel) {
        try {
            meshService?.setRemoteChannel(destNum, channel.toByteArray())
        } catch (ex: RemoteException) {
            errormsg("Can't set channel on radio ${ex.message}")
        }
    }

    val provideLocation = object : MutableLiveData<Boolean>(preferences.getBoolean("provide-location", false)) {
        override fun setValue(value: Boolean) {
            super.setValue(value)

            preferences.edit {
                this.putBoolean("provide-location", value)
            }
        }
    }

    fun setOwner(user: User) {
        setRemoteOwner(myNodeNum ?: return, user)
    }

    fun setRemoteOwner(destNum: Int, user: User) {
        try {
            // Note: we use ?. here because we might be running in the emulator
            meshService?.setRemoteOwner(destNum, user.toByteArray())
            _radioConfigState.update { it.copy(userConfig = user) }
        } catch (ex: RemoteException) {
            errormsg("Can't set username on device, is device offline? ${ex.message}")
        }
    }

    val adminChannelIndex: Int /** matches [MeshService.adminChannelIndex] **/
        get() = channelSet.settingsList.indexOfFirst { it.name.equals("admin", ignoreCase = true) }
            .coerceAtLeast(0)

    /**
     * Write the persisted packet data out to a CSV file in the specified location.
     */
    fun saveMessagesCSV(uri: Uri) {
        viewModelScope.launch(Dispatchers.Main) {
            // Extract distances to this device from position messages and put (node,SNR,distance) in
            // the file_uri
            val myNodeNum = myNodeNum ?: return@launch

            // Capture the current node value while we're still on main thread
            val nodes = nodeDB.nodes.value ?: emptyMap()

            val positionToPos: (MeshProtos.Position?) -> Position? = { meshPosition ->
                meshPosition?.let { Position(it) }.takeIf {
                    it?.isValid() == true
                }
            }

            writeToUri(uri) { writer ->
                // Create a map of nodes keyed by their ID
                val nodesById = nodes.values.associateBy { it.num }.toMutableMap()
                val nodePositions = mutableMapOf<Int, MeshProtos.Position?>()

                writer.appendLine("date,time,from,sender name,sender lat,sender long,rx lat,rx long,rx elevation,rx snr,distance,hop limit,payload")

                // Packets are ordered by time, we keep most recent position of
                // our device in localNodePosition.
                val dateFormat = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss", Locale.getDefault())
                meshLogRepository.getAllLogsInReceiveOrder(Int.MAX_VALUE).first().forEach { packet ->
                    // If we get a NodeInfo packet, use it to update our position data (if valid)
                    packet.nodeInfo?.let { nodeInfo ->
                        positionToPos.invoke(nodeInfo.position)?.let {
                            nodePositions[nodeInfo.num] = nodeInfo.position
                        }
                    }

                    packet.meshPacket?.let { proto ->
                        // If the packet contains position data then use it to update, if valid
                        packet.position?.let { position ->
                            positionToPos.invoke(position)?.let {
                                nodePositions[proto.from] = position
                            }
                        }

                        // Filter out of our results any packet that doesn't report SNR.  This
                        // is primarily ADMIN_APP.
                        if (proto.rxSnr != 0.0f) {
                            val rxDateTime = dateFormat.format(packet.received_date)
                            val rxFrom = proto.from.toUInt()
                            val senderName = nodesById[proto.from]?.user?.longName ?: ""

                            // sender lat & long
                            val senderPosition = nodePositions[proto.from]
                            val senderPos = positionToPos.invoke(senderPosition)
                            val senderLat = senderPos?.latitude ?: ""
                            val senderLong = senderPos?.longitude ?: ""

                            // rx lat, long, and elevation
                            val rxPosition = nodePositions[myNodeNum]
                            val rxPos = positionToPos.invoke(rxPosition)
                            val rxLat = rxPos?.latitude ?: ""
                            val rxLong = rxPos?.longitude ?: ""
                            val rxAlt = rxPos?.altitude ?: ""
                            val rxSnr = "%f".format(proto.rxSnr)

                            // Calculate the distance if both positions are valid

                            val dist = if (senderPos == null || rxPos == null) {
                                ""
                            } else {
                                positionToMeter(
                                    rxPosition!!, // Use rxPosition but only if rxPos was valid
                                    senderPosition!! // Use senderPosition but only if senderPos was valid
                                ).roundToInt().toString()
                            }

                            val hopLimit = proto.hopLimit

                            val payload = when {
                                proto.decoded.portnumValue != Portnums.PortNum.TEXT_MESSAGE_APP_VALUE -> "<${proto.decoded.portnum}>"
                                proto.hasDecoded() -> "\"" + proto.decoded.payload.toStringUtf8()
                                    .replace("\"", "\\\"") + "\""
                                proto.hasEncrypted() -> "${proto.encrypted.size()} encrypted bytes"
                                else -> ""
                            }

                            //  date,time,from,sender name,sender lat,sender long,rx lat,rx long,rx elevation,rx snr,distance,hop limit,payload
                            writer.appendLine("$rxDateTime,$rxFrom,$senderName,$senderLat,$senderLong,$rxLat,$rxLong,$rxAlt,$rxSnr,$dist,$hopLimit,$payload")
                        }
                    }
                }
            }
        }
    }

    private suspend inline fun writeToUri(uri: Uri, crossinline block: suspend (BufferedWriter) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                app.contentResolver.openFileDescriptor(uri, "wt")?.use { parcelFileDescriptor ->
                    FileWriter(parcelFileDescriptor.fileDescriptor).use { fileWriter ->
                        BufferedWriter(fileWriter).use { writer ->
                            block.invoke(writer)
                        }
                    }
                }
            } catch (ex: FileNotFoundException) {
                errormsg("Can't write file error: ${ex.message}")
            }
        }
    }

    private val _deviceProfile = MutableStateFlow<DeviceProfile?>(null)
    val deviceProfile: StateFlow<DeviceProfile?> = _deviceProfile

    fun setDeviceProfile(deviceProfile: DeviceProfile?) {
        _deviceProfile.value = deviceProfile
    }

    fun importProfile(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            app.contentResolver.openInputStream(uri).use { inputStream ->
                val bytes = inputStream?.readBytes()
                val protobuf = DeviceProfile.parseFrom(bytes)
                _deviceProfile.value = protobuf
            }
        } catch (ex: Exception) {
            val error = "${ex.javaClass.simpleName}: ${ex.message}"
            errormsg("Import DeviceProfile error: ${ex.message}")
            setResponseStateError(error)
        }
    }

    fun exportProfile(uri: Uri) = viewModelScope.launch {
        val profile = deviceProfile.value ?: return@launch
        writeToUri(uri, profile)
        _deviceProfile.value = null
    }

    private suspend fun writeToUri(uri: Uri, message: MessageLite) = withContext(Dispatchers.IO) {
        try {
            app.contentResolver.openFileDescriptor(uri, "wt")?.use { parcelFileDescriptor ->
                FileOutputStream(parcelFileDescriptor.fileDescriptor).use { outputStream ->
                    message.writeTo(outputStream)
                }
            }
        } catch (ex: FileNotFoundException) {
            errormsg("Can't write file error: ${ex.message}")
        }
    }

    fun installProfile(protobuf: DeviceProfile) = with(protobuf) {
        _deviceProfile.value = null
        // meshService?.beginEditSettings()
        if (hasLongName() || hasShortName()) ourNodeInfo.value?.user?.let {
            val user = it.copy(
                longName = if (hasLongName()) longName else it.longName,
                shortName = if (hasShortName()) shortName else it.shortName
            )
            setOwner(user.toProto())
        }
        if (hasChannelUrl()) {
            setChannels(ChannelSet(Uri.parse(channelUrl)))
        }
        if (hasConfig()) {
            setConfig(config { device = config.device })
            setConfig(config { position = config.position })
            setConfig(config { power = config.power })
            setConfig(config { network = config.network })
            setConfig(config { display = config.display })
            setConfig(config { lora = config.lora })
            setConfig(config { bluetooth = config.bluetooth })
        }
        if (hasModuleConfig()) moduleConfig.let {
            setModuleConfig(moduleConfig { mqtt = it.mqtt })
            setModuleConfig(moduleConfig { serial = it.serial })
            setModuleConfig(moduleConfig { externalNotification = it.externalNotification })
            setModuleConfig(moduleConfig { storeForward = it.storeForward })
            setModuleConfig(moduleConfig { rangeTest = it.rangeTest })
            setModuleConfig(moduleConfig { telemetry = it.telemetry })
            setModuleConfig(moduleConfig { cannedMessage = it.cannedMessage })
            setModuleConfig(moduleConfig { audio = it.audio })
            setModuleConfig(moduleConfig { remoteHardware = it.remoteHardware })
        }
        // meshService?.commitEditSettings()
    }

    fun addQuickChatAction(name: String, value: String, mode: QuickChatAction.Mode) {
        viewModelScope.launch(Dispatchers.Main) {
            val action = QuickChatAction(0, name, value, mode, _quickChatActions.value.size)
            quickChatActionRepository.insert(action)
        }
    }

    fun deleteQuickChatAction(action: QuickChatAction) {
        viewModelScope.launch(Dispatchers.Main) {
            quickChatActionRepository.delete(action)
        }
    }

    fun updateQuickChatAction(
        action: QuickChatAction,
        name: String?,
        message: String?,
        mode: QuickChatAction.Mode?
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            val newAction = QuickChatAction(
                action.uuid,
                name ?: action.name,
                message ?: action.message,
                mode ?: action.mode,
                action.position
            )
            quickChatActionRepository.update(newAction)
        }
    }

    fun updateActionPositions(actions: List<QuickChatAction>) {
        viewModelScope.launch(Dispatchers.Main) {
            for (position in actions.indices) {
                quickChatActionRepository.setItemPosition(actions[position].uuid, position)
            }
        }
    }

    fun clearPacketResponse() {
        _radioConfigState.update { it.copy(responseState = ResponseState.Empty) }
    }

    fun setResponseStateLoading(route: String) {
        _radioConfigState.value = RadioConfigState(
            route = route,
            responseState = ResponseState.Loading(total = 1),
        )
    }

    fun setResponseStateTotal(total: Int) {
        _radioConfigState.update { state ->
            if (state.responseState is ResponseState.Loading) {
                state.copy(responseState = state.responseState.copy(total = total))
            } else {
                state // Return the unchanged state for other response states
            }
        }
    }

    private fun setResponseStateError(error: String) {
        _radioConfigState.update { it.copy(responseState = ResponseState.Error(error)) }
    }

    private fun incrementCompleted() {
        _radioConfigState.update { state ->
            if (state.responseState is ResponseState.Loading) {
                val increment = state.responseState.completed + 1
                state.copy(responseState = state.responseState.copy(completed = increment))
            } else {
                state // Return the unchanged state for other response states
            }
        }
    }

    fun clearRemoteChannelList() {
        _radioConfigState.update { it.copy(channelList = emptyList()) }
    }

    fun setRemoteChannelList(list: List<ChannelSettings>) {
        _radioConfigState.update { it.copy(channelList = list) }
    }

    private val _tracerouteResponse = MutableLiveData<String?>(null)
    val tracerouteResponse: LiveData<String?> get() = _tracerouteResponse

    fun clearTracerouteResponse() {
        _tracerouteResponse.value = null
    }

    private fun processPacketResponse(log: MeshLog?) {
        val destNum = destNode.value?.num ?: return
        val packet = log?.meshPacket ?: return
        val data = packet.decoded
        val destStr = destNum.toUInt()
        val fromStr = packet.from.toUInt()
        requestId.value = null

        if (data?.portnumValue == Portnums.PortNum.TRACEROUTE_APP_VALUE) {
            val parsed = MeshProtos.RouteDiscovery.parseFrom(data.payload)
            fun nodeName(num: Int) = nodeDB.nodesByNum?.get(num)?.user?.longName
                ?: app.getString(R.string.unknown_username)

            _tracerouteResponse.value = buildString {
                append("${nodeName(packet.to)} --> ")
                parsed.routeList.forEach { num -> append("${nodeName(num)} --> ") }
                append(nodeName(packet.from))
            }
        }
        if (data?.portnumValue == Portnums.PortNum.ROUTING_APP_VALUE) {
            val parsed = MeshProtos.Routing.parseFrom(data.payload)
            debug("packet for destNum $destStr received ${parsed.errorReason} from $fromStr")
            if (parsed.errorReason != MeshProtos.Routing.Error.NONE) {
                setResponseStateError(parsed.errorReason.toString())
            } else if (packet.from == destNum) {
                _radioConfigState.update { it.copy(responseState = ResponseState.Success(true)) }
            }
        }
        if (data?.portnumValue == Portnums.PortNum.ADMIN_APP_VALUE) {
            val parsed = AdminProtos.AdminMessage.parseFrom(data.payload)
            debug("packet for destNum $destStr received ${parsed.payloadVariantCase} from $fromStr")
            if (destNum != packet.from) {
                setResponseStateError("Unexpected sender: $fromStr instead of $destStr.")
                return
            }
            // check destination: lora config or channel editor
            val goChannels = radioConfigState.value.route == ConfigRoute.CHANNELS.name
            when (parsed.payloadVariantCase) {
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CHANNEL_RESPONSE -> {
                    val response = parsed.getChannelResponse
                    incrementCompleted()
                    // Stop once we get to the first disabled entry
                    if (response.role != ChannelProtos.Channel.Role.DISABLED) {
                        _radioConfigState.update { state ->
                            val updatedList = state.channelList.toMutableList().apply {
                                add(response.index, response.settings)
                            }
                            state.copy(channelList = updatedList)
                        }
                        if (response.index + 1 < maxChannels && goChannels) {
                            // Not done yet, request next channel
                            getChannel(destNum, response.index + 1)
                        } else {
                            // Received max channels, get lora config (for default channel names)
                            getConfig(destNum, AdminProtos.AdminMessage.ConfigType.LORA_CONFIG_VALUE)
                        }
                    } else {
                        // Received last channel, get lora config (for default channel names)
                        setResponseStateTotal(radioConfigState.value.channelList.size + 1)
                        getConfig(destNum, AdminProtos.AdminMessage.ConfigType.LORA_CONFIG_VALUE)
                    }
                }

                AdminProtos.AdminMessage.PayloadVariantCase.GET_OWNER_RESPONSE -> {
                    _radioConfigState.update { it.copy(userConfig = parsed.getOwnerResponse) }
                    incrementCompleted()
                }

                AdminProtos.AdminMessage.PayloadVariantCase.GET_CONFIG_RESPONSE -> {
                    val response = parsed.getConfigResponse
                    if (response.payloadVariantCase.number == 0) { // PAYLOADVARIANT_NOT_SET
                        setResponseStateError(response.payloadVariantCase.name)
                    }
                    _radioConfigState.update { it.copy(radioConfig = response) }
                    incrementCompleted()
                }

                AdminProtos.AdminMessage.PayloadVariantCase.GET_MODULE_CONFIG_RESPONSE -> {
                    val response = parsed.getModuleConfigResponse
                    if (response.payloadVariantCase.number == 0) { // PAYLOADVARIANT_NOT_SET
                        setResponseStateError(response.payloadVariantCase.name)
                    }
                    _radioConfigState.update { it.copy(moduleConfig = response) }
                    incrementCompleted()
                }

                AdminProtos.AdminMessage.PayloadVariantCase.GET_CANNED_MESSAGE_MODULE_MESSAGES_RESPONSE -> {
                    _radioConfigState.update {
                        it.copy(cannedMessageMessages = parsed.getCannedMessageModuleMessagesResponse)
                    }
                    incrementCompleted()
                    getModuleConfig(destNum, AdminProtos.AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG_VALUE)
                }

                AdminProtos.AdminMessage.PayloadVariantCase.GET_RINGTONE_RESPONSE -> {
                    _radioConfigState.update { it.copy(ringtone = parsed.getRingtoneResponse) }
                    incrementCompleted()
                    getModuleConfig(destNum, AdminProtos.AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG_VALUE)
                }

                else -> TODO()
            }
        }
    }
}
