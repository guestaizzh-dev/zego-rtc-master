// RTC 通话分段耗时诊断日志
//
// 目的：定位 Web 端通话首帧链路慢在哪一段（获取参数/建引擎/登录/推流/拉流/首帧）。
// 约束：仅记录耗时与上下文，绝不打印 token / server / secret 等敏感信息。
// 用法：
//   const trace = createRtcTrace({ roomID, userID, role, streamID })
//   trace.start('loginRoom')
//   await this.client.loginRoom(...)
//   trace.end('loginRoom')
//   trace.mark('roomStreamUpdate_ADD', { remoteStreamID })        // 瞬时点，首次为准
//   trace.fail('loginRoom', error)                                // 失败点
//   trace.print()                                                 // 末尾聚合输出
//
// 所有日志经 console.warn 实时输出，便于真机 / 控制台抓取；
// 末尾 print() 给出一份分段聚合报告。

const TAG = 'RTC-TRACE'
const REPORT_TAG = 'RTC-TRACE-REPORT'

const safeCode = error => {
    if (!error) return ''
    return String(error.errorCode || error.code || '').slice(0, 64)
}

const pad = (text, width) => {
    const str = String(text)
    if (str.length >= width) return str
    return str + ' '.repeat(width - str.length)
}

export const createRtcTrace = ({ roomID = '', userID = '', role = '', streamID = '' } = {}) => {
    const context = { roomID, userID, role, streamID }
    const origin = Date.now()
    // 单点事件：首次记录为准，重名不覆盖（避免多次 roomStreamUpdate 污染首帧指标）
    const marks = {}
    // 配对事件：start/end 算 cost
    const startAt = {}
    const costs = {}
    // 全部事件时序（含重试 attempt），便于离线回看
    const events = []
    let firstFrameReported = false

    const stamp = () => ({ ts: Date.now(), elapsed: Date.now() - origin })

    const line = (name, extra = {}) => {
        const { ts, elapsed } = stamp()
        const parts = [
            `${name} elapsed=${elapsed}ms`,
        ]
        if (extra.cost !== undefined) parts.push(`cost=${extra.cost}ms`)
        if (extra.gap !== undefined) parts.push(`gap=${extra.gap}ms`)
        if (extra.streamID) parts.push(`stream=${extra.streamID}`)
        if (extra.ts !== undefined) parts.push(`ts=${extra.ts}`)
        if (extra.code) parts.push(`code=${extra.code}`)
        parts.push(`room=${context.roomID} user=${context.userID} role=${context.role} stream=${context.streamID}`)
        return `[${TAG}] ${parts.join(' ')}`
    }

    return {
        mark (name, extra = {}) {
            if (marks[name]) return this // 首次为准
            const point = stamp()
            marks[name] = point
            events.push({ name, ...point, ...extra })
            console.warn(line(name, { ts: point.ts, ...extra }))
            // 首帧到达即输出一次完整报告，便于即时定位
            if (name === 'playerStateUpdate_PLAYING' && !firstFrameReported) {
                firstFrameReported = true
                this.print()
            }
            return this
        },
        start (name, extra = {}) {
            const point = stamp()
            startAt[name] = point.ts
            console.warn(line(`${name}_start`, { ts: point.ts, ...extra }))
            return this
        },
        end (name, extra = {}) {
            const startedAt = startAt[name]
            const endPoint = stamp()
            if (startedAt === undefined) {
                console.warn(line(`${name}_end`, { ts: endPoint.ts, ...extra }))
                return this
            }
            const cost = endPoint.ts - startedAt
            costs[name] = cost
            events.push({ name: `${name}_end`, ...endPoint, cost, ...extra })
            console.warn(line(`${name}_end`, { cost, ts: endPoint.ts, ...extra }))
            return this
        },
        fail (name, error) {
            const point = stamp()
            const code = safeCode(error)
            events.push({ name: `${name}_fail`, ...point, code })
            console.warn(line(`${name}_fail`, { ts: point.ts, code }))
            return this
        },
        report () {
            return {
                context: { ...context },
                origin,
                marks: { ...marks },
                costs: { ...costs },
                events: events.slice(),
            }
        },
        print () {
            const c = costs
            const m = marks
            const ordered = [
                { key: 'fetchRtcSession', label: '获取房间参数' },
                { key: 'createEngine', label: '建引擎' },
                { key: 'checkSystemRequirements', label: '能力检测' },
                { key: 'loginRoom', label: '登录房间' },
                { key: 'createZegoStream', label: '打开摄像头/麦克风' },
                { key: 'startPublishingStream', label: '推流' },
                { key: 'startLocalRecording', label: '推流后录制(阻塞项)' },
                { key: 'startPlayingStream', label: '拉流' },
                { key: 'remoteView_play', label: '远端预览 play' },
            ]
            const rows = ordered.map(item => {
                const cost = c[item.key]
                const text = cost === undefined ? 'N/A' : `${cost}ms`
                return `  ${pad(item.label, 22)} ${text}`
            })

            // 跨端关联点：roomStreamUpdate_ADD 的绝对 ts
            const addPoint = m.roomStreamUpdate_ADD
            if (addPoint) {
                rows.push(`  ${pad('roomStreamUpdate_ADD', 22)} ts=${addPoint.ts}`)
            }
            // 推流上线点：publisherStateUpdate_PUBLISHING（与对端 roomStreamUpdate_ADD 离线关联）
            const pubPoint = m.publisherStateUpdate_PUBLISHING
            if (pubPoint) {
                rows.push(`  ${pad('publisherStateUpdate_PUBLISHING', 22)} ts=${pubPoint.ts}`)
            }

            // 关键总耗时
            const firstFrame = m.playerStateUpdate_PLAYING
            const publishDone = c.startPublishingStream !== undefined
                && c.createZegoStream !== undefined
                && c.loginRoom !== undefined
            const segments = []
            if (firstFrame) {
                segments.push(`首帧 page_enter->PLAYING ${firstFrame.elapsed}ms`)
            }
            if (publishDone) {
                const publishTotal = (c.fetchRtcSession || 0)
                    + (c.createEngine || 0)
                    + (c.checkSystemRequirements || 0)
                    + (c.loginRoom || 0)
                    + (c.createZegoStream || 0)
                    + (c.startPublishingStream || 0)
                segments.push(`推流完成 page_enter->publish ${publishTotal}ms`)
            }
            // remoteView_play -> playerStateUpdate_PLAYING：首帧渲染耗时
            if (m.remoteView_play && firstFrame) {
                const renderCost = firstFrame.ts - m.remoteView_play.ts
                rows.push(`  ${pad('渲染 play->PLAYING', 22)} ${renderCost}ms`)
            }

            console.warn(`[${REPORT_TAG}] room=${context.roomID} user=${context.userID} role=${context.role} stream=${context.streamID}`)
            console.warn(rows.join('\n'))
            if (segments.length) {
                console.warn(segments.map(s => `  ── ${s} ──`).join('\n'))
            }
            return this
        },
    }
}
