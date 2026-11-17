import { getModules } from '@valenced/modules/finders'
import { withProps } from '@valenced/modules/finders/filters'
import { instead } from '@valenced/patcher'
import { callNativeMethod } from '@valenced/modules/native'
import { ToastActionCreators } from '@valenced/discord/actions'

type UploadItem = Record<string, any>
type ProcessingResult = {
  changed: boolean
  cancelled?: boolean
  uri?: string
  filename?: string
  mime?: string
  bytes?: number
}

function candidateFile(item: UploadItem): UploadItem {
  return item?.file && typeof item.file === 'object' ? item.file : item
}

function candidateUri(item: UploadItem): string | undefined {
  const file = candidateFile(item)
  for (const v of [file.uri, file.localUri, file.path, file.fileUri, item.uri, item.localUri, item.path]) {
    if (typeof v === 'string' && /^(content:\/\/|file:\/\/|\/)/.test(v)) {
      return v.startsWith('/') ? `file://${v}` : v
    }
  }
}

function looksLikeJpeg(item: UploadItem): boolean {
  const file = candidateFile(item)
  const name = String(file.filename ?? file.name ?? item.name ?? '')
  const mime = String(file.mimeType ?? file.type ?? item.mimeType ?? item.type ?? '')
  return /\.(jpe?g)$/i.test(name) || /image\/jpeg/i.test(mime)
}

function replaceAsset(item: UploadItem, result: ProcessingResult): void {
  if (!result.uri || !result.filename || !result.mime) return
  const target = candidateFile(item)
  const path = result.uri.replace(/^file:\/\//, '')
  for (const obj of new Set([item, target])) {
    if (!obj || typeof obj !== 'object') continue
    for (const prop of ['uri', 'localUri', 'fileUri']) {
      if (prop in obj || prop === 'uri') obj[prop] = result.uri
    }
    if ('path' in obj) obj.path = path
    for (const prop of ['name', 'filename']) {
      if (prop in obj || prop === 'filename') obj[prop] = result.filename
    }
    for (const prop of ['type', 'mimeType']) {
      if (prop in obj || prop === 'mimeType') obj[prop] = result.mime
    }
    if (typeof result.bytes === 'number') {
      if ('size' in obj) obj.size = result.bytes
      if ('fileSize' in obj) obj.fileSize = result.bytes
    }
  }
}

function message(text: string, critical = false) {
  ToastActionCreators.open('MOTION_PHOTO_SENDER', {
    text,
    variant: critical ? 'critical' : 'default',
  })
}

export default plugin({
  start({ cleanup, logger, plugin: currentPlugin }) {
    let active = true
    cleanup(() => { active = false })
    cleanup(getModules(withProps('uploadLocalFiles'), mod => {
      logger.log('Motion Photo Sender: uploadLocalFiles found')
      cleanup(instead(mod, 'uploadLocalFiles', async function(args, original) {
        const payload = args[0] as UploadItem | undefined
        const items = payload?.items ?? payload?.files ?? payload?.uploads
        if (!active || !Array.isArray(items)) {
          return Reflect.apply(original, this, args)
        }
        try {
          const kept: UploadItem[] = []
          for (const item of items as UploadItem[]) {
            if (!active || !looksLikeJpeg(item)) {
              kept.push(item)
              continue
            }
            const uri = candidateUri(item)
            if (!uri) {
              kept.push(item)
              continue
            }
            const result = await callNativeMethod('dev.motion.photo.process', [uri]) as ProcessingResult | null
            if (!result?.changed) {
              if (!result?.cancelled) kept.push(item)
              continue
            }
            replaceAsset(item, result)
            kept.push(item)
          }
          if (kept.length === 0) return
          if (payload) {
            if (Array.isArray(payload.items)) payload.items = kept
            else if (Array.isArray(payload.files)) payload.files = kept
            else if (Array.isArray(payload.uploads)) payload.uploads = kept
          }
          return Reflect.apply(original, this, args)
        } catch (error) {
          currentPlugin.reportError(error)
          message('Motion Photo processing failed. Sending originals.', true)
          return Reflect.apply(original, this, args)
        }
      }))
    }, { max: 1 }))
  },
})

declare module '@valenced/modules/native' {
  export interface NativeMethods {
    'dev.motion.photo.process': [
      args: [uri: string],
      returnValue: ProcessingResult | null,
    ]
  }
}
