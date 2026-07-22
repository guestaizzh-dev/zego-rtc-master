<template>
    <div class="wrapper">
        <div class="content">
            <div id="remoteVideo" class="main-window" ref="remote">
                <div class="camera-off-mask" v-show="remoteCameraOff">
                    <span class="camera-off-text">对方摄像头已关闭</span>
                </div>
                <span class="loading-text" v-show="isDesc">{{ desc }}</span>
            </div>
            <div id="localVideo" class="sub-window" ref="local">
                <div class="camera-off-mask local" v-show="cameraOff">
                    <span class="camera-off-text">摄像头已关闭</span>
                </div>
            </div>
            <div class="status-panel">
                <span :class="{ network: true, warn: networkWarning }">{{ networkStatus }}</span>
                <span class="room-label">房间：{{ roomID }}</span>
                <button class="fullscreen-btn" type="button" @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</button>
            </div>
            <div class="remote-panel" v-if="remoteUsers.length">
                <div class="remote-title">远端用户</div>
                <div class="remote-user" v-for="user in remoteUsers" :key="user.streamID">
                    <span class="remote-name">{{ user.userName || user.userID || user.streamID }}</span>
                    <span class="remote-state" v-if="user.audioMuted">麦克风已关</span>
                    <span class="remote-state" v-if="user.videoMuted">摄像头已关</span>
                </div>
            </div>
        </div>
        <ul class="tab-bar">
            <li :class="{ silence: true, isSilence }" @click="setOrRelieveSilence"></li>
            <li class="over" @click="handleOver"></li>
            <li :class="{ stop: true, 'camera-off': cameraOff }" @click="toggleCamera"></li>
        </ul>
    </div>
</template>

<script>
    import axios from 'axios'
    import { message } from '../../components/message'
    import {
        createRtcStreamID,
        createStableRtcUserID,
        endRtcRoom,
        getRtcSession,
        getRtcRoomStatus,
        heartbeatRtcRoom,
        isTrustedRtcParentCommand,
        leaveRtcRoom,
    } from '../../common'

    const RETRY_DELAY = 1200
    const MAX_RETRY = 2
    const HEARTBEAT_INTERVAL = 3000
    const RECORDING_STOP_UI_TIMEOUT = 2000
    const REMOTE_NO_PLAY_CONFIRM_DELAY = 3500
    const ZEGO_DECODE_FAIL_ERROR_CODE = 1003081
    const ZEGO_TRANSCODE_TEMPLATE_ID = Number(process.env.VUE_APP_ZEGO_TRANSCODE_TEMPLATE_ID || 0)
    const ZEGO_TRANSCODE_ON_DECODE_FAIL = process.env.VUE_APP_ZEGO_TRANSCODE_ON_DECODE_FAIL !== 'false'

    export default {
        name: 'single',
        data () {
            return {
                isSilence: false,
                isDesc: true,
                cameraOff: false,
                remoteCameraOff: false,
                cameraTogglePending: false,
                desc: '等待对方加入视频问诊...',
                client: null,
                localStream: null,
                localView: null,
                remoteStreamID: '',
                remoteStream: null,
                remoteView: null,
                roomID: '',
                userID: '',
                streamID: '',
                leaseID: '',
                role: 'guest',
                ticket: '',
                orderID: '',
                callID: '',
                recordEnabled: false,
                session: null,
                recordingTaskID: '',
                leaving: false,
                terminating: false,
                leavePromise: null,
                joining: false,
                cleanupCompleted: false,
                pageHideSent: false,
                pageHideEndRoom: false,
                renewingToken: false,
                publishRetry: 0,
                playRetry: 0,
                autoLeaveTimer: null,
                heartbeatTimer: null,
                operationGeneration: 0,
                publishingRetry: false,
                playingRetry: false,
                remoteNoPlayTimer: null,
                pendingNoPlayStreamID: '',
                decodeWarningAt: 0,
                remoteUsers: [],
                networkStatus: '未连接',
                networkWarning: false,
                isFullscreen: false,
            }
        },
        mounted () {
            this.roomID = this.$route.query.channelName || this.$route.query.roomID || ''
            this.role = this.$route.query.role || 'guest'
            this.ticket = this.$route.query.ticket || ''
            this.orderID = this.$route.query.orderID || this.$route.query.orderId || ''
            this.callID = this.$route.query.callID || ''
            this.recordEnabled = this.normalizeQueryBoolean(this.$route.query.record)
            this.userID = this.$route.query.userID || createStableRtcUserID(this.roomID, this.role)
            this.streamID = createRtcStreamID(this.roomID, this.userID)
            document.addEventListener('fullscreenchange', this.handleFullscreenChange)
            window.addEventListener('message', this.handleParentCommand)
            window.addEventListener('pagehide', this.handlePageHide)
            this.notifyParent('mounted', '通话页面已就绪')
            this.joinChannel()
            this.autoLeaveTimer = setTimeout(() => {
                console.warn('通话已达到10分钟上限，自动安全退出房间')
                this.terminateCall({
                    stopRecording: true,
                    endRoom: false,
                    reason: 'timeout',
                    messageText: '通话已达到10分钟上限',
                })
            }, 1000 * 60 * 10)
        },
        beforeDestroy () {
            document.removeEventListener('fullscreenchange', this.handleFullscreenChange)
            window.removeEventListener('message', this.handleParentCommand)
            window.removeEventListener('pagehide', this.handlePageHide)
            if (!this.cleanupCompleted) {
                this.sendPageHideBeacons()
            }
        },
        methods: {
            async joinChannel () {
                if (this.joining || this.client) {
                    return
                }
                if (!this.roomID) {
                    message('房间号为空')
                    this.returnJoin()
                    return
                }
                if (!window.ZegoExpressEngine) {
                    message('ZEGO Web SDK 加载失败')
                    this.returnJoin()
                    return
                }

                try {
                    this.joining = true
                    this.desc = '正在进入视频问诊...'
                    const userName = this.userID
                    const session = await this.fetchRtcSession()
                    this.session = session
                    this.userID = session.userID
                    this.streamID = session.streamID
                    this.leaseID = session.leaseID || ''
                    if (this.terminating) {
                        return
                    }

                    this.client = new window.ZegoExpressEngine(Number(session.appID), session.server || '')
                    this.client.setDebugVerbose(false)
                    this.bindEvents()

                    const capability = await this.client.checkSystemRequirements()
                    if (!capability.webRTC) {
                        throw new Error('当前浏览器不支持 WebRTC')
                    }
                    if (this.terminating) {
                        return
                    }

                    const loggedIn = await this.client.loginRoom(
                        this.roomID,
                        session.token,
                        { userID: this.userID, userName },
                        { userUpdate: true }
                    )
                    if (!loggedIn) {
                        throw new Error('登录 ZEGO 房间失败')
                    }
                    if (this.terminating) {
                        return
                    }

                    await this.initLocalStream()
                    if (this.terminating) {
                        return
                    }
                    this.startHeartbeat()
                    this.networkStatus = '已连接'
                    this.networkWarning = false
                    this.desc = '等待对方加入视频问诊...'
                    console.info('已加入通话', {
                        roomID: this.roomID,
                        userID: this.userID,
                        streamID: this.streamID,
                    })
                    this.notifyParent('ready', '通话已连接')
                } catch (error) {
                    if (this.terminating) {
                        console.info('加入流程已取消，等待执行通话清理')
                        return
                    }
                    console.error('加入房间失败', error)
                    const tip = this.formatError(error, '加入房间失败')
                    this.notifyParent('error', tip)
                    message(tip)
                    const result = await this.leaveChannel(false, false, false)
                    if (window.parent && window.parent !== window) {
                        this.notifyParent('closed', tip, {
                            reason: 'join-failed',
                            success: false,
                            recordingStopped: !result || result.recordingStopped !== false,
                            roomActionSucceeded: !result || result.roomActionSucceeded !== false,
                        })
                    } else {
                        setTimeout(() => {
                            this.$router.push({
                                path: '/',
                                query: { path: 'single' },
                            })
                        }, 2000)
                    }
                } finally {
                    this.joining = false
                }
            },
            fetchRtcSession () {
                return getRtcSession({
                    roomID: this.roomID,
                    userID: this.userID,
                    streamID: this.streamID,
                    leaseID: this.leaseID,
                    role: this.role,
                    ticket: this.ticket,
                    orderID: this.orderID,
                    clientType: 'web',
                })
            },
            bindEvents () {
                this.client.on('roomStateChanged', (roomID, reason, errorCode, extendedData) => {
                    console.warn('房间状态', { roomID, reason, errorCode, extendedData })
                    this.updateNetworkStatus(reason, errorCode)
                    if (/KICK|KICKOUT/i.test(String(reason)) || errorCode === 1002050) {
                        const tip = '你已被移出房间，或当前账号已在其他设备进入'
                        this.desc = tip
                        this.notifyParent('error', tip)
                        message(tip)
                        this.terminateCall({
                            stopRecording: true,
                            endRoom: false,
                            reason: 'kicked',
                            messageText: tip,
                        })
                        return
                    }
                    if (reason === 'RECONNECTING') {
                        this.networkStatus = '正在重连'
                        this.networkWarning = true
                    }
                    if (reason === 'RECONNECTED') {
                        this.networkStatus = '已恢复连接'
                        this.networkWarning = false
                    }
                    if (reason === 'RECONNECT_FAILED' || reason === 'LOGIN_FAILED') {
                        this.desc = '房间连接失败，请重新进入'
                        this.notifyParent('error', this.desc)
                        message(this.desc)
                        this.terminateCall({
                            stopRecording: true,
                            endRoom: false,
                            reason: 'connection-failed',
                            messageText: this.desc,
                        })
                        return
                    }
                    if (reason === 'LOGOUT') {
                        this.stopHeartbeat()
                    }
                    if (errorCode) {
                        this.desc = '房间连接异常，请稍后重试'
                        this.notifyParent('error', `房间连接异常：${errorCode}`)
                        message(`房间连接异常：${errorCode}`)
                    }
                })

                this.client.on('publisherStateUpdate', result => {
                    console.warn('推流状态', result)
                    if (result && result.errorCode) {
                        this.retryPublishing(result.errorCode)
                    }
                })

                this.client.on('playerStateUpdate', result => {
                    console.warn('拉流状态', result)
                    if (result && result.state === 'NO_PLAY' && result.streamID && result.streamID === this.remoteStreamID) {
                        this.scheduleRemoteNoPlay(result.streamID)
                    }
                    if (result && result.streamID && result.streamID === this.remoteStreamID && (result.state === 'PLAYING' || result.state === 'PLAY_REQUESTING')) {
                        this.clearRemoteNoPlay()
                    }
                    if (result && result.errorCode && result.streamID && result.streamID === this.remoteStreamID) {
                        this.handleRemotePlayError(result)
                    }
                })

                this.client.on('tokenWillExpire', async roomID => {
                    console.warn('ZEGO Token 即将过期', { roomID })
                    await this.renewToken()
                })

                this.client.on('roomStreamUpdate', async (roomID, updateType, streamList) => {
                    console.warn('流更新', { roomID, updateType, streamList })
                    if (updateType === 'ADD') {
                        const remote = streamList.find(item => item.streamID !== this.streamID)
                        if (remote) {
                            try {
                                await this.playRemoteStream(remote.streamID, remote)
                            } catch (error) {
                                console.error('播放远端流失败', error)
                                message(this.formatError(error, '播放远端画面失败'))
                            }
                        }
                    }
                    if (updateType === 'DELETE') {
                        for (const item of streamList) {
                            if (item.streamID === this.remoteStreamID) {
                                this.stopRemoteStream(item.streamID)
                                await this.handleRemoteDeleted()
                            }
                        }
                    }
                })
                this.client.on('roomUserUpdate', (roomID, updateType, userList) => {
                    console.warn('用户更新', { roomID, updateType, userList })
                    if (updateType === 'DELETE' && Array.isArray(userList)) {
                        userList.forEach(user => {
                            const userID = user && user.userID
                            const matched = this.remoteUsers.find(item => item.userID === userID)
                            if (!matched) {
                                return
                            }
                            if (matched.streamID === this.remoteStreamID) {
                                this.stopRemoteStream(matched.streamID)
                                this.handleRemoteDeleted()
                            } else {
                                this.removeRemoteUser(matched.streamID)
                            }
                        })
                    }
                })
                this.client.on('playQualityUpdate', (streamID, quality) => {
                    const level = this.qualityLevel(quality)
                    if (level >= 4) {
                        this.networkStatus = '远端网络较差'
                        this.networkWarning = true
                    }
                })
                this.client.on('publishQualityUpdate', (streamID, quality) => {
                    const level = this.qualityLevel(quality)
                    if (level >= 4) {
                        this.networkStatus = '本地网络较差'
                        this.networkWarning = true
                    }
                })
                this.client.on('remoteCameraStatusUpdate', (streamID, status) => {
                    const muted = status === 'MUTE' || status === false
                    this.updateRemoteMedia(streamID, { videoMuted: muted })
                    // Keep the remote stream active so video can resume without a new pull request.
                    this.applyRemoteVideoVisible(!muted)
                    this.remoteCameraOff = muted
                })
                this.client.on('remoteMicStatusUpdate', (streamID, status) => {
                    this.updateRemoteMedia(streamID, { audioMuted: status === 'MUTE' || status === false })
                })
            },
            async initLocalStream () {
                try {
                    this.localStream = await this.client.createZegoStream({
                        camera: {
                            audio: true,
                            video: true,
                            videoQuality: 2,
                        },
                    })
                } catch (error) {
                    throw new Error(this.formatError(error, '无法打开摄像头或麦克风，请检查浏览器授权和设备占用'))
                }
                this.playLocalPreview()
                await this.publishLocalStream()
            },
            playLocalPreview () {
                const local = this.$refs.local
                local.innerHTML = ''
                if (this.client.createLocalStreamView) {
                    this.localView = this.client.createLocalStreamView(this.localStream)
                    this.localView.play(local, { objectFit: 'cover', enableAutoplayDialog: true })
                } else if (this.localStream.playVideo) {
                    this.localStream.playVideo(local, { objectFit: 'cover', enableAutoplayDialog: true })
                }
                this.applyLocalPreviewCameraOff(this.cameraOff)
            },
            async publishLocalStream () {
                try {
                    await this.client.startPublishingStream(this.streamID, this.localStream)
                    this.publishRetry = 0
                    await this.startLocalRecording()
                } catch (error) {
                    if (this.publishRetry < MAX_RETRY) {
                        this.publishRetry += 1
                        await this.wait(RETRY_DELAY)
                        return this.publishLocalStream()
                    }
                    throw new Error(this.formatError(error, '本地推流失败，请检查网络后重试'))
                }
            },
            async playRemoteStream (streamID, streamInfo = {}) {
                if (this.remoteStreamID === streamID) {
                    this.clearRemoteNoPlay()
                    return
                }
                if (this.remoteStreamID && this.remoteStreamID !== streamID) {
                    console.warn('房间已存在远端用户，忽略新的远端流', streamID)
                    return
                }
                this.clearRemoteNoPlay()
                this.remoteStreamID = streamID
                this.upsertRemoteUser(streamID, streamInfo)
                try {
                    const playOptions = this.buildRemotePlayOptions()
                    console.info('开始拉取远端流', {
                        streamID,
                        playOptions: playOptions || {},
                    })
                    this.remoteStream = playOptions
                        ? await this.client.startPlayingStream(streamID, playOptions)
                        : await this.client.startPlayingStream(streamID)
                    this.clearRemoteMediaElements()
                    this.remoteView = this.client.createRemoteStreamView(this.remoteStream)
                    this.remoteView.play(this.$refs.remote, { objectFit: 'contain', enableAutoplayDialog: true })
                    this.isDesc = false
                    this.playRetry = 0
                    this.remoteCameraOff = false
                    this.applyRemoteVideoVisible(true)
                    await this.startLocalRecording()
                } catch (error) {
                    this.remoteStreamID = ''
                    this.removeRemoteUser(streamID)
                    throw error
                }
            },
            stopRemoteStream (streamID = this.remoteStreamID) {
                if (!streamID || !this.client) {
                    return
                }
                this.clearRemoteNoPlay()
                const stoppedStreamID = streamID
                if (this.remoteView && typeof this.remoteView.stop === 'function') {
                    this.remoteView.stop()
                }
                try {
                    this.client.stopPlayingStream(streamID)
                } catch (error) {
                    console.warn('停止远端拉流失败', { streamID, error })
                }
                if (this.remoteStreamID === streamID) {
                    this.remoteStreamID = ''
                }
                this.remoteStream = null
                this.remoteView = null
                this.removeRemoteUser(stoppedStreamID)
                this.clearRemoteMediaElements()
                this.remoteCameraOff = false
            },
            clearRemoteMediaElements () {
                const remote = this.$refs.remote
                if (!remote || !remote.children) {
                    return
                }
                Array.from(remote.children).forEach(child => {
                    if (!child.classList || !child.classList.contains('loading-text')) {
                        child.remove()
                    }
                })
            },
            buildRemotePlayOptions () {
                if (!this.hasTranscodeFallback()) {
                    return null
                }
                return {
                    codecTemplateID: ZEGO_TRANSCODE_TEMPLATE_ID,
                    transcodeOnDecodeFail: ZEGO_TRANSCODE_ON_DECODE_FAIL,
                }
            },
            hasTranscodeFallback () {
                return Number.isFinite(ZEGO_TRANSCODE_TEMPLATE_ID) && ZEGO_TRANSCODE_TEMPLATE_ID > 0
            },
            isRemoteDecodeFail (result = {}) {
                if (Number(result.errorCode) === ZEGO_DECODE_FAIL_ERROR_CODE) {
                    return true
                }
                const detail = `${result.extendedData || ''} ${result.message || ''}`
                return /B-frame|codec/i.test(detail)
            },
            scheduleRemoteNoPlay (streamID) {
                if (this.leaving || !streamID || streamID !== this.remoteStreamID) {
                    return
                }
                this.clearRemoteNoPlay()
                this.pendingNoPlayStreamID = streamID
                this.remoteNoPlayTimer = setTimeout(() => {
                    if (this.leaving || this.pendingNoPlayStreamID !== streamID || this.remoteStreamID !== streamID) {
                        return
                    }
                    console.warn('远端拉流持续 NO_PLAY，等待 SDK 内部恢复或流删除事件', { streamID })
                    this.networkStatus = '远端网络异常，正在恢复'
                    this.networkWarning = true
                }, REMOTE_NO_PLAY_CONFIRM_DELAY)
            },
            clearRemoteNoPlay () {
                if (this.remoteNoPlayTimer) {
                    clearTimeout(this.remoteNoPlayTimer)
                }
                this.remoteNoPlayTimer = null
                this.pendingNoPlayStreamID = ''
            },
            async retryPublishing (errorCode) {
                if (this.leaving || this.publishingRetry || !this.client || !this.localStream || this.publishRetry >= MAX_RETRY) {
                    message(`本地推流异常：${errorCode}`)
                    return
                }
                const generation = this.operationGeneration
                this.publishingRetry = true
                this.publishRetry += 1
                await this.wait(RETRY_DELAY)
                try {
                    if (this.leaving || generation !== this.operationGeneration || !this.client) return
                    await this.client.stopPublishingStream(this.streamID)
                    await this.client.startPublishingStream(this.streamID, this.localStream)
                    this.publishRetry = 0
                } catch (error) {
                    console.warn('重试推流失败', error)
                } finally {
                    this.publishingRetry = false
                }
            },
            handleRemotePlayError (result = {}) {
                const errorCode = result.errorCode
                if (!errorCode || this.leaving) {
                    return
                }
                if (this.isRemoteDecodeFail(result)) {
                    const now = Date.now()
                    if (!this.decodeWarningAt || now - this.decodeWarningAt > 5000) {
                        console.warn('远端视频解码异常，保留当前拉流并等待 SDK 自恢复', {
                            result,
                            transcodeEnabled: this.hasTranscodeFallback(),
                            codecTemplateID: ZEGO_TRANSCODE_TEMPLATE_ID,
                        })
                        this.decodeWarningAt = now
                    }
                    this.networkWarning = true
                    if (this.hasTranscodeFallback()) {
                        this.networkStatus = '远端视频解码异常，正在恢复'
                    } else {
                        this.networkStatus = '远端视频解码异常，等待恢复'
                    }
                    if (result.state === 'NO_PLAY') {
                        this.scheduleRemoteNoPlay(result.streamID)
                    }
                    return
                }
                console.warn('远端拉流异常，等待 SDK 内部恢复', result)
                this.networkStatus = `远端网络异常：${errorCode}`
                this.networkWarning = true
                if (result.state === 'NO_PLAY') {
                    this.scheduleRemoteNoPlay(result.streamID)
                }
            },
            async renewToken () {
                if (this.renewingToken || !this.client) {
                    return
                }
                try {
                    this.renewingToken = true
                    const session = await this.fetchRtcSession()
                    this.session = session
                    this.leaseID = session.leaseID || this.leaseID
                    await this.client.renewToken(session.token)
                    console.info('ZEGO Token 已续期')
                } catch (error) {
                    console.error('ZEGO Token 续期失败', error)
                    message(this.formatError(error, '通话凭证续期失败，请结束后重新进入'))
                } finally {
                    this.renewingToken = false
                }
            },
            startHeartbeat () {
                this.stopHeartbeat()
                this.heartbeatTimer = setInterval(() => {
                    this.notifyRoomHeartbeat()
                }, HEARTBEAT_INTERVAL)
                this.notifyRoomHeartbeat()
            },
            stopHeartbeat () {
                if (this.heartbeatTimer) {
                    clearInterval(this.heartbeatTimer)
                    this.heartbeatTimer = null
                }
            },
            notifyRoomHeartbeat () {
                if (!this.roomID || !this.userID) {
                    return
                }
                heartbeatRtcRoom({
                    roomID: this.roomID,
                    userID: this.userID,
                    leaseID: this.leaseID,
                    role: this.role,
                    orderID: this.orderID,
                    clientType: 'web',
                }).catch(error => {
                    if (this.isRoomEndedError(error)) {
                        this.terminateCall({
                            stopRecording: this.role === 'doctor',
                            endRoom: false,
                            roomAlreadyEnded: true,
                            reason: 'remote-ended',
                            messageText: '对方已结束通话',
                        })
                        return
                    }
                    console.warn('房间心跳失败', error)
                })
            },
            notifyRoomLeave () {
                if (!this.roomID || !this.userID) {
                    return Promise.resolve()
                }
                return leaveRtcRoom({
                    roomID: this.roomID,
                    userID: this.userID,
                    leaseID: this.leaseID,
                    role: this.role,
                    orderID: this.orderID,
                    clientType: 'web',
                })
            },
            notifyRoomEnd () {
                if (!this.roomID || !this.userID) {
                    return this.notifyRoomLeave()
                }
                if (!this.isDoctorRole()) {
                    return this.notifyRoomLeave()
                }
                return endRtcRoom({
                    roomID: this.roomID,
                    userID: this.userID,
                    leaseID: this.leaseID,
                    role: this.role,
                    orderID: this.orderID,
                    clientType: 'web',
                })
            },
            async startLocalRecording () {
                if (this.role !== 'doctor' || !this.recordEnabled || this.recordingTaskID) {
                    return
                }
                try {
                    const { data } = await axios.post('/api/recording/start', {
                        roomID: this.roomID,
                        orderID: this.orderID,
                    }, { timeout: 10000 })
                    this.recordingTaskID = data.taskID || ''
                    if (data.status) {
                        this.notifyParent('recording', '录制中')
                        message('已请求本地录制')
                    }
                } catch (error) {
                    const msg = error.response && error.response.data && error.response.data.error
                        ? error.response.data.error
                        : '本地录制未启动'
                    console.warn('本地录制启动失败', msg)
                    this.notifyParent('error', msg)
                    message(`${msg}，不影响当前通话`)
                }
            },
            sendRecordingStopBeacon () {
                if (!navigator.sendBeacon || !this.recordingTaskID) {
                    return false
                }
                try {
                    return navigator.sendBeacon('/api/recording/stop', new Blob([JSON.stringify({
                        roomID: this.roomID,
                        taskID: this.recordingTaskID,
                    })], {
                        type: 'application/json',
                    }))
                } catch (error) {
                    console.warn('停止录制兜底请求失败', error)
                    return false
                }
            },
            async stopLocalRecording (uiTimeout = RECORDING_STOP_UI_TIMEOUT) {
                if (!this.recordingTaskID) {
                    return true
                }
                let timer = null
                const stopRequest = axios.post('/api/recording/stop', {
                    roomID: this.roomID,
                    taskID: this.recordingTaskID,
                }, { timeout: 10000 }).then(() => {
                    this.recordingTaskID = ''
                    return true
                }).catch(error => {
                    console.warn('本地录制停止失败', error)
                    return false
                })
                const timeoutGuard = new Promise(resolve => {
                    timer = setTimeout(() => resolve('timeout'), uiTimeout)
                })
                const result = await Promise.race([stopRequest, timeoutGuard])
                if (timer) {
                    clearTimeout(timer)
                }
                if (result === 'timeout') {
                    console.warn('停止录制接口响应较慢，先继续退出通话')
                    this.sendRecordingStopBeacon()
                    stopRequest.then(success => {
                        if (!success && this.recordingTaskID) {
                            this.sendRecordingStopBeacon()
                        }
                    })
                    return 'pending'
                }
                return result === true
            },
            returnJoin (time = 2000) {
                if (window.parent && window.parent !== window) {
                    this.terminateCall({
                        stopRecording: true,
                        endRoom: false,
                        reason: 'return',
                        messageText: this.desc || '通话已关闭',
                    })
                    return
                }
                setTimeout(() => {
                    this.$router.push({
                        path: '/',
                        query: { path: 'single' },
                    })
                }, time)
            },
            setOrRelieveSilence () {
                if (!this.localStream || !this.client) {
                    message('当前不能操作麦克风')
                    return
                }
                this.isSilence = !this.isSilence
                this.client.mutePublishStreamAudio(this.localStream, this.isSilence)
            },
            toggleCamera () {
                if (!this.localStream || !this.client) {
                    message('当前不能操作摄像头')
                    return
                }
                if (this.cameraTogglePending) {
                    return
                }
                this.cameraTogglePending = true
                const next = !this.cameraOff
                try {
                    this.client.mutePublishStreamVideo(this.localStream, next, false)
                    this.cameraOff = next
                    this.applyLocalPreviewCameraOff(next)
                } catch (error) {
                    console.warn('切换摄像头失败', error)
                    message('摄像头切换失败，请重试')
                } finally {
                    this.$nextTick(() => { this.cameraTogglePending = false })
                }
            },
            applyLocalPreviewCameraOff (off) {
                const el = this.$refs.local && this.$refs.local.querySelector('video')
                if (!el) {
                    return
                }
                el.style.display = off ? 'none' : ''
                if (!off) {
                    try {
                        if (el.play && el.paused) {
                            el.play().catch(() => {})
                        }
                    } catch (error) {
                        // The preview is allowed to recover asynchronously.
                    }
                }
            },
            async handleOver () {
                if (this.terminating) {
                    return
                }
                const text = '确定结束本次视频问诊吗？'
                if (!window.confirm(text)) {
                    return
                }
                this.isDesc = true
                this.desc = '正在结束通话，请稍候...'
                this.networkStatus = '正在结束'
                this.networkWarning = false
                const shouldEndRoom = this.isDoctorRole()
                this.pageHideEndRoom = shouldEndRoom
                this.notifyParent('closing', '正在结束通话', {
                    reason: 'user',
                })
                await this.terminateCall({
                    stopRecording: shouldEndRoom,
                    endRoom: shouldEndRoom,
                    reason: 'user',
                    messageText: shouldEndRoom ? '通话已结束' : '已离开通话',
                })
            },
            async leaveChannel (stopRecording = true, endRoom = false, notifyParent = true, roomAlreadyEnded = false) {
                if (this.leavePromise) {
                    return this.leavePromise
                }
                this.leaving = true
                this.leavePromise = (async () => {
                    let recordingStopped = true
                    let roomActionSucceeded = true
                    let roomActionError = ''
                    this.operationGeneration += 1
                    this.clearRemoteNoPlay()
                    this.stopHeartbeat()
                    if (this.autoLeaveTimer) {
                        clearTimeout(this.autoLeaveTimer)
                        this.autoLeaveTimer = null
                    }
                    try {
                        this.stopRemoteStream()
                    } catch (error) {
                        console.warn('停止远端播放失败', error)
                    }
                    try {
                        if (this.streamID && this.client) this.client.stopPublishingStream(this.streamID)
                    } catch (error) {
                        console.warn('停止推流失败', error)
                    }
                    try {
                        if (this.localStream && this.client) this.client.destroyStream(this.localStream)
                    } catch (error) {
                        console.warn('销毁本地流失败', error)
                    }
                    const stopRecordingPromise = stopRecording
                        ? this.stopLocalRecording().catch(error => {
                            console.warn('停止录制失败', error)
                            return false
                        })
                        : Promise.resolve(true)
                    const logoutPromise = this.roomID && this.client
                        ? Promise.resolve().then(() => this.client.logoutRoom(this.roomID)).catch(error => {
                            console.warn('退出 ZEGO 房间失败', error)
                            return false
                        })
                        : Promise.resolve(true)
                    const roomActionPromise = (roomAlreadyEnded
                        ? Promise.resolve(true)
                        : (endRoom ? this.notifyRoomEnd() : this.notifyRoomLeave())).catch(error => {
                        roomActionSucceeded = false
                        roomActionError = this.formatError(error, endRoom ? '结束房间失败' : '释放房间席位失败')
                        console.warn(endRoom ? '结束房间失败' : '释放房间席位失败', error)
                        message(roomActionError)
                        return false
                    })
                    recordingStopped = await stopRecordingPromise
                    await Promise.all([logoutPromise, roomActionPromise])
                    try {
                        if (this.client && this.client.destroyEngine) this.client.destroyEngine()
                    } catch (error) {
                        console.warn('销毁 ZEGO 引擎失败', error)
                    } finally {
                        this.client = null
                        this.localStream = null
                        this.localView = null
                        this.remoteStream = null
                        this.remoteView = null
                        this.remoteStreamID = ''
                        this.remoteUsers = []
                        this.networkStatus = '已离开'
                        this.networkWarning = false
                        this.cameraOff = false
                        this.isSilence = false
                        this.remoteCameraOff = false
                    }
                    const recordingStopPending = recordingStopped === 'pending'
                    const success = recordingStopped !== false && roomActionSucceeded
                    this.cleanupCompleted = success
	                    if (notifyParent) {
	                        this.notifyParent('closed', roomActionError || (endRoom ? '通话已结束' : '已离开通话'), {
	                            reason: endRoom ? 'ended' : 'leave',
	                            endRoom: endRoom === true,
	                            role: this.role,
	                            success,
	                            recordingStopped: recordingStopped === true,
	                            recordingStopPending,
	                            roomActionSucceeded,
                        })
                    }
                    return {
                        success,
                        recordingStopped: recordingStopped === true,
                        recordingStopPending,
                        roomActionSucceeded,
                        message: roomActionError,
                    }
                })()
                return this.leavePromise
            },
            async terminateCall ({
                stopRecording = true,
                endRoom = false,
                roomAlreadyEnded = false,
                reason = 'close',
                messageText = '',
            } = {}) {
                if (this.terminating) {
                    return this.leavePromise
                }
                this.terminating = true
                if (endRoom) {
                    this.pageHideEndRoom = true
                }
                await this.waitForJoinSettlement()
                let result
                try {
                    result = await this.leaveChannel(stopRecording, endRoom, false, roomAlreadyEnded)
                } catch (error) {
                    console.error('清理通话资源失败', error)
                    result = {
                        success: false,
                        recordingStopped: false,
                        roomActionSucceeded: false,
                        message: this.formatError(error, '清理通话资源失败'),
                    }
                }
                const finalMessage = result && result.message
                    ? result.message
                    : (messageText || (endRoom ? '通话已结束' : '已离开通话'))
	                this.notifyParent('closed', finalMessage, {
	                    reason,
	                    endRoom: endRoom === true,
	                    role: this.role,
	                    success: !result || result.success !== false,
	                    recordingStopped: !result || result.recordingStopped !== false,
	                    recordingStopPending: result && result.recordingStopPending === true,
	                    roomActionSucceeded: !result || result.roomActionSucceeded !== false,
                })
                if (!window.parent || window.parent === window) {
                    this.$router.push({
                        path: '/',
                        query: { path: 'single' },
                    })
                    window.close()
                }
                return result
            },
            async waitForJoinSettlement (timeout = 12000) {
                const startedAt = Date.now()
                while (this.joining && Date.now() - startedAt < timeout) {
                    await this.wait(50)
                }
                if (this.joining) {
                    console.warn('等待加入流程结束超时，继续执行退出清理')
                }
            },
            updateNetworkStatus (reason, errorCode) {
                if (errorCode) {
                    this.networkStatus = `连接异常：${errorCode}`
                    this.networkWarning = true
                    return
                }
                if (reason === 'LOGINING') {
                    this.networkStatus = '连接中'
                    this.networkWarning = false
                } else if (reason === 'LOGINED') {
                    this.networkStatus = '已连接'
                    this.networkWarning = false
                } else if (reason === 'RECONNECTING') {
                    this.networkStatus = '正在重连'
                    this.networkWarning = true
                } else if (reason === 'RECONNECTED') {
                    this.networkStatus = '已恢复连接'
                    this.networkWarning = false
                } else if (reason === 'RECONNECT_FAILED' || reason === 'LOGIN_FAILED' || reason === 'LOGOUT') {
                    this.networkStatus = '连接断开'
                    this.networkWarning = true
                }
            },
            async handleRemoteDeleted () {
                this.isDesc = true
                this.desc = '对方已离开，正在确认房间状态...'
                try {
                    const status = await getRtcRoomStatus(this.roomID)
                    if (status && status.status === 'ENDED') {
                        this.desc = '本次视频问诊已结束'
                        message(this.desc)
                        await this.terminateCall({
                            stopRecording: true,
                            endRoom: false,
                            reason: 'remote-ended',
                            messageText: this.desc,
                        })
                        return
                    }
                } catch (error) {
                    console.warn('查询房间状态失败', error)
                }
                this.desc = '对方已离开，等待重新加入...'
            },
            qualityLevel (quality) {
                const value = quality && quality.stats && quality.stats.video
                    ? quality.stats.video.videoQuality
                    : quality && quality.video && quality.video.videoQuality
                const level = Number(value)
                return Number.isFinite(level) ? level : 0
            },
            upsertRemoteUser (streamID, streamInfo = {}) {
                const user = streamInfo.user || {}
                const existing = this.remoteUsers.find(item => item.streamID === streamID)
                const next = {
                    streamID,
                    userID: user.userID || streamInfo.userID || streamID,
                    userName: user.userName || streamInfo.userName || user.userID || streamID,
                    audioMuted: false,
                    videoMuted: false,
                }
                if (existing) {
                    Object.assign(existing, next)
                    this.remoteUsers = this.remoteUsers.slice()
                } else {
                    this.remoteUsers = this.remoteUsers.concat(next)
                }
            },
            removeRemoteUser (streamID) {
                this.remoteUsers = this.remoteUsers.filter(item => item.streamID !== streamID)
            },
            updateRemoteMedia (streamID, patch) {
                this.remoteUsers = this.remoteUsers.map(item => {
                    if (item.streamID !== streamID && item.userID !== streamID) {
                        return item
                    }
                    return Object.assign({}, item, patch)
                })
            },
            applyRemoteVideoVisible (visible) {
                const remote = this.$refs.remote
                if (!remote) {
                    return
                }
                remote.querySelectorAll('video').forEach(el => {
                    el.style.visibility = visible ? 'visible' : 'hidden'
                })
            },
            async toggleFullscreen () {
                const element = this.$el
                try {
                    if (!document.fullscreenElement) {
                        if (!document.fullscreenEnabled || !element.requestFullscreen) {
                            message('当前浏览器不支持页面全屏')
                            return
                        }
                        await element.requestFullscreen()
                        return
                    }
                    if (document.exitFullscreen) {
                        await document.exitFullscreen()
                    }
                } catch (error) {
                    console.warn('切换全屏失败', error)
                    message('切换全屏失败')
                }
            },
            handleFullscreenChange () {
                this.isFullscreen = Boolean(document.fullscreenElement)
            },
            wait (time) {
                return new Promise(resolve => setTimeout(resolve, time))
            },
            normalizeQueryBoolean (value) {
                if (value === true) {
                    return true
                }
                const text = String(value || '').trim().toLowerCase()
                return text === 'true' || text === '1' || text === 'yes'
            },
            parentTargetOrigin () {
                try {
                    if (document.referrer) {
                        return new URL(document.referrer).origin
                    }
                } catch (error) {
                    console.warn('解析父页面来源失败', error)
                }
                return '*'
            },
            handleParentCommand (event) {
                if (!window.parent || window.parent === window || event.source !== window.parent) {
                    return
                }
                const expectedOrigin = this.parentTargetOrigin()
                const payload = event.data || {}
                if (!isTrustedRtcParentCommand({
                    event,
                    parentWindow: window.parent,
                    expectedOrigin,
                    callID: this.callID,
                })) {
                    return
                }
                const shouldEndRoom = payload.action === 'terminate' && payload.endRoom === true && this.isDoctorRole()
                if (payload.action === 'terminate' && payload.endRoom === true) {
                    this.pageHideEndRoom = shouldEndRoom
                }
                this.terminateCall({
                    stopRecording: this.isDoctorRole(),
                    endRoom: shouldEndRoom,
                    reason: payload.action,
                    messageText: payload.action === 'reload' ? '通话已安全退出，正在刷新' : '通话已关闭',
                })
            },
            handlePageHide () {
                if (!this.cleanupCompleted) {
                    this.sendPageHideBeacons()
                }
            },
            sendPageHideBeacons () {
                if (this.pageHideSent || !navigator.sendBeacon || !this.roomID || !this.userID) {
                    return
                }
                this.pageHideSent = true
                const sendJson = (url, payload) => {
                    try {
                        navigator.sendBeacon(url, new Blob([JSON.stringify(payload)], {
                            type: 'application/json',
                        }))
                    } catch (error) {
                        console.warn('页面退出兜底请求失败', error)
                    }
                }
                if (this.recordingTaskID) {
                    sendJson('/api/recording/stop', {
                        roomID: this.roomID,
                        taskID: this.recordingTaskID,
                    })
                }
                sendJson(this.pageHideEndRoom ? '/api/rtc/end' : '/api/rtc/leave', {
                    roomID: this.roomID,
                    userID: this.userID,
                    leaseID: this.leaseID,
                    role: this.role,
                    orderID: this.orderID,
                    clientType: 'web',
                })
            },
            isDoctorRole () {
                return String(this.role || '').toLowerCase() === 'doctor'
            },
            notifyParent (action, messageText = '', extra = {}) {
                if (!window.parent || window.parent === window) {
                    return
                }
                try {
                    window.parent.postMessage(Object.assign({
                        type: 'CHB_RTC_EVENT',
                        action,
                        message: messageText,
                        roomID: this.roomID,
                        orderID: this.orderID,
                        userID: this.userID,
                        callID: this.callID,
                    }, extra), this.parentTargetOrigin())
                } catch (error) {
                    console.warn('通知父页面失败', error)
                }
            },
            formatError (error, fallback) {
                if (!error) {
                    return fallback
                }
	                const responseMsg = error.response && error.response.data && error.response.data.error
	                if (responseMsg) {
	                    return responseMsg
	                }
	                const responseData = error.response && error.response.data
	                if (responseData && (responseData.msg || responseData.message)) {
	                    return responseData.msg || responseData.message
	                }
	                if (typeof responseData === 'string' && responseData) {
	                    return responseData
	                }
                const code = error.errorCode || error.code
                const rawMessage = error.message || error.extendedData || ''
                if (/permission|denied|notallowed|NotAllowedError/i.test(rawMessage)) {
                    return '未获得摄像头或麦克风权限，请在浏览器地址栏授权后重新进入'
                }
                if (/notfound|NotFoundError|device/i.test(rawMessage)) {
                    return '未检测到可用摄像头或麦克风，请检查设备后重试'
                }
                if (code) {
                    return `${fallback}：${code}`
                }
                return rawMessage || fallback
            },
            isRoomEndedError (error) {
                return this.formatError(error, '').indexOf('当前订单已结束') !== -1
            },
        },
    }
</script>

<style scoped lang="less">
.wrapper {
    height: 100vh;
    background: #111318;
    display: flex;
    flex-direction: column;

    .content {
        flex: 1;
        position: relative;

        .main-window {
            height: 100vh;
            width: 100%;
            background: #000;

            .camera-off-mask {
                position: absolute;
                top: 0;
                left: 0;
                width: 100%;
                height: 100%;
                background: #000;
                z-index: 8;
                display: flex;
                align-items: center;
                justify-content: center;

                .camera-off-text {
                    color: #b8bcc4;
                    font-size: 18px;
                    font-weight: 400;
                    text-align: center;
                }
            }

            .loading-text {
                display: block;
                padding-top: 48vh;
                width: 100%;
                text-align: center;
                line-height: 36px;
                color: #fff;
                font-weight: 400;
                font-size: 22px;
            }
        }

        .sub-window {
            width: 165px;
            height: 95px;
            background: #000;
            position: absolute;
            z-index: 9;
            right: 8px;
            top: 8px;
            border: 1px solid #FFFFFF;
            overflow: hidden;

            .camera-off-mask {
                position: absolute;
                top: 0;
                left: 0;
                width: 100%;
                height: 100%;
                background: #000;
                z-index: 2;
                display: flex;
                align-items: center;
                justify-content: center;

                .camera-off-text {
                    color: #b8bcc4;
                    font-size: 12px;
                    font-weight: 400;
                    text-align: center;
                    padding: 0 6px;
                }
            }
        }

        .status-panel {
            position: absolute;
            left: 16px;
            top: 14px;
            z-index: 12;
            display: flex;
            align-items: center;
            gap: 10px;
            max-width: calc(100% - 210px);
            color: #fff;
            font-size: 13px;

            .network,
            .room-label,
            .fullscreen-btn {
                height: 28px;
                line-height: 28px;
                padding: 0 10px;
                border-radius: 4px;
                background: rgba(17, 24, 39, 0.72);
                white-space: nowrap;
            }

            .network.warn {
                background: rgba(185, 28, 28, 0.82);
            }

            .fullscreen-btn {
                border: 0;
                color: #fff;
                cursor: pointer;
            }
        }

        .remote-panel {
            position: absolute;
            left: 16px;
            top: 52px;
            z-index: 12;
            min-width: 188px;
            max-width: 280px;
            padding: 10px 12px;
            border-radius: 6px;
            background: rgba(17, 24, 39, 0.72);
            color: #fff;
            font-size: 13px;

            .remote-title {
                margin-bottom: 6px;
                color: rgba(255, 255, 255, 0.72);
            }

            .remote-user {
                display: flex;
                align-items: center;
                gap: 6px;
                min-height: 24px;
            }

            .remote-name {
                max-width: 130px;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }

            .remote-state {
                padding: 2px 6px;
                border-radius: 4px;
                background: rgba(185, 28, 28, 0.82);
                font-size: 12px;
            }
        }
    }

    .tab-bar {
        position: absolute;
        bottom: 16px;
        width: 100%;
        height: 54px;
        list-style: none;
        display: flex;
        justify-content: center;
        align-items: center;
        color: #fff;

        li {
            height: 54px;
            width: 125px;
            cursor: pointer;

            &.silence {
                background: url("../../assets/img/icon/silence.png") no-repeat center;
                background-size: 60px 54px;

                &:hover {
                    background: url("../../assets/img/icon/silence-hover.png") no-repeat center;
                    background-size: 60px 54px;
                }

                &:active {
                    background: url("../../assets/img/icon/silence-click.png") no-repeat center;
                    background-size: 60px 54px;
                }

                &.isSilence {
                    background: url("../../assets/img/icon/relieve-silence.png") no-repeat center;
                    background-size: 60px 54px;

                    &:hover {
                        background: url("../../assets/img/icon/relieve-silence-hover.png") no-repeat center;
                        background-size: 60px 54px;
                    }

                    &:active {
                        background: url("../../assets/img/icon/relieve-silence-click.png") no-repeat center;
                        background-size: 60px 54px;
                    }
                }
            }

            &.over {
                background: url("../../assets/img/icon/over.png") no-repeat center;
                background-size: 68px 36px;

                &:hover {
                    background: url("../../assets/img/icon/over-hover.png") no-repeat center;
                    background-size: 68px 36px;
                }

                &:active {
                    background: url("../../assets/img/icon/over-click.png") no-repeat center;
                    background-size: 68px 36px;
                }
            }

            &.stop {
                background: url("../../assets/img/icon/stop.png") no-repeat center;
                background-size: 60px 54px;

                &:hover {
                    background: url("../../assets/img/icon/stop-hover.png") no-repeat center;
                    background-size: 60px 54px;
                }

                &:active {
                    background: url("../../assets/img/icon/stop-click.png") no-repeat center;
                    background-size: 60px 54px;
                }

                &.camera-off {
                    background: url("../../assets/img/icon/open.png") no-repeat center;
                    background-size: 60px 54px;

                    &:hover {
                        background: url("../../assets/img/icon/open-hover.png") no-repeat center;
                        background-size: 60px 54px;
                    }

                    &:active {
                        background: url("../../assets/img/icon/open-click.png") no-repeat center;
                        background-size: 60px 54px;
                    }
                }
            }
        }
    }
}
</style>
