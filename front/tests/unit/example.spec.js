import { isTrustedRtcParentCommand } from '@/common'

describe('RTC 父子窗口指令校验', () => {
    const parentWindow = {}
    const baseEvent = {
        source: parentWindow,
        origin: 'https://temptest.chbzg.com.cn',
        data: {
            type: 'CHB_RTC_COMMAND',
            action: 'terminate',
            callID: 'call_123',
        },
    }

    it('只接受来源、窗口、callID 和动作都匹配的指令', () => {
        expect(isTrustedRtcParentCommand({
            event: baseEvent,
            parentWindow,
            expectedOrigin: baseEvent.origin,
            callID: 'call_123',
        })).toBe(true)
    })

    it.each([
        ['错误来源', { origin: 'https://example.com' }],
        ['错误窗口', { source: {} }],
        ['错误 callID', { data: { ...baseEvent.data, callID: 'call_other' } }],
        ['未知动作', { data: { ...baseEvent.data, action: 'open' } }],
        ['错误消息类型', { data: { ...baseEvent.data, type: 'OTHER_EVENT' } }],
    ])('拒绝%s', (name, patch) => {
        expect(isTrustedRtcParentCommand({
            event: { ...baseEvent, ...patch },
            parentWindow,
            expectedOrigin: baseEvent.origin,
            callID: 'call_123',
        })).toBe(false)
    })

    it('referrer 不可用时仍要求窗口与 callID 一致', () => {
        expect(isTrustedRtcParentCommand({
            event: baseEvent,
            parentWindow,
            expectedOrigin: '*',
            callID: 'call_123',
        })).toBe(true)
        expect(isTrustedRtcParentCommand({
            event: { ...baseEvent, source: {} },
            parentWindow,
            expectedOrigin: '*',
            callID: 'call_123',
        })).toBe(false)
    })
})
