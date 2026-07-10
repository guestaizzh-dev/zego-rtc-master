import axios from 'axios'

const RTC_REQUEST_CONFIG = { timeout: 10000 }

export const getRtcSession = ({
    roomID,
    userID,
    streamID,
    leaseID,
    ttlSec,
    role,
    ticket,
    orderID,
    clientType,
}) => axios.post('/api/rtc/session', {
    roomID,
    userID,
    streamID,
    leaseID,
    ttlSec,
    role,
    ticket,
    orderID,
    clientType,
}, RTC_REQUEST_CONFIG).then(({ data }) => data)

export const createRtcUserID = (role = 'guest') => {
    const safeRole = String(role || 'guest').replace(/[^A-Za-z0-9_-]/g, '_')
    return `web_${safeRole}_${Math.random().toString(36).slice(2, 8)}`
}

export const createStableRtcUserID = (roomID, role = 'guest') => {
    const safeRoomID = String(roomID || 'room').replace(/[^A-Za-z0-9_-]/g, '_')
    const safeRole = String(role || 'guest').replace(/[^A-Za-z0-9_-]/g, '_')
    const storageKey = `chbzg_rtc_user_${safeRoomID}_${safeRole}`
    try {
        const existing = window.localStorage && window.localStorage.getItem(storageKey)
        if (existing && /^[A-Za-z0-9_.:-]{1,64}$/.test(existing)) {
            return existing
        }
        const userID = createRtcUserID(safeRole)
        if (window.localStorage) {
            window.localStorage.setItem(storageKey, userID)
        }
        return userID
    } catch (error) {
        return createRtcUserID(safeRole)
    }
}

export const createRtcStreamID = (roomID, userID) => {
    const source = String(roomID || '')
    let hash = 2166136261
    for (let index = 0; index < source.length; index += 1) {
        hash ^= source.charCodeAt(index)
        hash = Math.imul(hash, 16777619)
    }
    const roomHash = (hash >>> 0).toString(36)
    return `r${roomHash}_${userID}`.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 256)
}

export const heartbeatRtcRoom = payload => axios.post('/api/rtc/heartbeat', payload, RTC_REQUEST_CONFIG)

export const leaveRtcRoom = payload => axios.post('/api/rtc/leave', payload, RTC_REQUEST_CONFIG)

export const endRtcRoom = payload => axios.post('/api/rtc/end', payload, RTC_REQUEST_CONFIG)

export const getRtcRoomStatus = roomID => axios.get('/api/rtc/status', {
    params: { roomID },
    timeout: RTC_REQUEST_CONFIG.timeout,
}).then(({ data }) => data)

export const isTrustedRtcParentCommand = ({
    event,
    parentWindow,
    expectedOrigin,
    callID,
}) => {
    if (!event || !parentWindow || event.source !== parentWindow) {
        return false
    }
    if (expectedOrigin && expectedOrigin !== '*' && event.origin !== expectedOrigin) {
        return false
    }
    const payload = event.data || {}
    return payload.type === 'CHB_RTC_COMMAND' &&
        Boolean(payload.callID) &&
        payload.callID === callID &&
        (payload.action === 'terminate' || payload.action === 'reload')
}

export const checkBrowser = (type) => {
  const ua = navigator.userAgent.toLowerCase();
  const info = {
      ie: /msie/.test(ua) && !/opera/.test(ua),
      opera: /opera/.test(ua),
      safari: /version.*safari/.test(ua),
      chrome: /chrome/.test(ua),
      firefox: /gecko/.test(ua) && !/webkit/.test(ua)
  };
  return info[type] || false;
}
