import { ToastActionCreators } from '@valenced/discord/actions'
import { getModules } from '@valenced/modules/finders'
import { createFilterGenerator, withProps } from '@valenced/modules/finders/filters'
import { callNativeMethod } from '@valenced/modules/native'
import { after, instead } from '@valenced/patcher'
import { useEffect, useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import type {
  Filter, FilterGenerator, FilterScopes,
} from '@valenced/modules/finders/filters'
import type { FC, MemoExoticComponent, ReactElement, ReactNode } from 'react'

type Format = 'mp4' | 'mp4-cover' | 'gif' | 'photo'
type UploadItem = Record<string, any>
type ProcessingResult = {
  changed: boolean
  cancelled?: boolean
  uri?: string
  filename?: string
  mime?: string
  bytes?: number
}
type MediaImageProps = {
  item?: { node?: { image?: { uri?: string } } }
}

const motionCache = new Map<string, boolean>()
const selectedFormats = new Map<string, Format>()
let stopped = false

function safeKey(uri: string): string {
  try {
    return decodeURIComponent(uri).replace(/^file:\/\//, '').toLowerCase()
  } catch {
    return uri.toLowerCase()
  }
}

function candidateFile(item: UploadItem): UploadItem {
  return item?.file && typeof item.file === 'object' ? item.file : item
}

function candidateUri(item: UploadItem): string | undefined {
  const file = candidateFile(item)
  for (const value of [
    file.uri, file.localUri, file.path, file.fileUri,
    item.uri, item.localUri, item.path,
  ]) {
    if (typeof value === 'string' && /^(content:\/\/|file:\/\/|\/)/.test(value)) {
      return value.startsWith('/') ? `file://${value}` : value
    }
  }
}

function looksLikeJpeg(item: UploadItem): boolean {
  const file = candidateFile(item)
  const name = String(file.filename ?? file.name ?? item.name ?? '')
  const mime = String(file.mimeType ?? file.type ?? item.mimeType ?? item.type ?? '')
  return /\.jpe?g$/i.test(name) || /image\/jpeg/i.test(mime)
}

function formatFor(uri: string): Format | undefined {
  return selectedFormats.get(safeKey(uri))
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

function toast(text: string, critical = false) {
  ToastActionCreators.open('MOTION_PHOTO_SENDER', {
    text,
    variant: critical ? 'critical' : 'default',
  })
}

const styles = StyleSheet.create({
  wrapper: {
    position: 'relative',
  },
  badge: {
    position: 'absolute',
    right: 5,
    top: 5,
    minWidth: 32,
    height: 32,
    paddingHorizontal: 5,
    borderRadius: 18,
    borderWidth: 2,
    borderColor: '#FFFFFF',
    backgroundColor: '#1DB954',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 10,
    elevation: 5,
  },
  badgeChosen: {
    backgroundColor: '#087D3B',
  },
  badgeText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
    fontSize: 12,
  },
})

/** Button is rendered only when the original image has Motion Photo XMP. */
function MotionPhotoBadge({ uri }: { uri: string }) {
  const key = safeKey(uri)
  const [motion, setMotion] = useState(motionCache.get(key) ?? false)
  const [choice, setChoice] = useState<Format | undefined>(selectedFormats.get(key))
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const cached = motionCache.get(key)
    let cancelled = false
    setChoice(selectedFormats.get(key))
    if (cached !== undefined) {
      setMotion(cached)
      return
    }
    setMotion(false)
    void callNativeMethod('dev.motion.photo.inspect', [uri])
      .then(value => {
        if (cancelled) return
        motionCache.set(key, value === true)
        setMotion(value === true)
      })
      .catch(() => {
        if (!cancelled) setMotion(false)
      })
    return () => { cancelled = true }
  }, [key, uri])

  if (!motion || stopped) return null

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="Выбрать формат Motion Photo"
      style={[styles.badge, choice ? styles.badgeChosen : null]}
      disabled={busy}
      onPress={event => {
        event.stopPropagation()
        if (busy) return
        setBusy(true)
        void (async () => {
          const selected = await callNativeMethod('dev.motion.photo.chooseFormat', [])
          if (stopped || !['mp4', 'mp4-cover', 'gif', 'photo'].includes(String(selected))) return
          const format = selected as Format
          toast(format === 'mp4' ? 'Извлекаю оригинальный MP4 без перекодирования...' : `Конвертирую Motion Photo: ${format.toUpperCase()}...`)
          const processed = await callNativeMethod('dev.motion.photo.process', [uri, format]) as ProcessingResult | null
          if (!processed?.changed || !processed.uri || !processed.mime || !processed.filename) {
            throw new Error('Не удалось извлечь видео из выбранной фотографии.')
          }
          const shared = await callNativeMethod('dev.motion.photo.share', [
            processed.uri,
            processed.mime,
            processed.filename,
          ])
          if (shared !== true) throw new Error('Android не открыл отправку файла.')
          // Do not mark the original gallery attachment as converted.
          // This flow shares the newly created file rather than changing the gallery item.
          setChoice(format)
          toast(`Готово: ${format.toUpperCase()}. Подтверди отправку в Discord.`)
        })()
          .catch(error => {
            const detail = error instanceof Error ? error.message : String(error)
            toast(`Motion Photo: ${detail}`, true)
          })
          .finally(() => setBusy(false))
      }}
    >
      <Text style={styles.badgeText}>{busy ? '...' : choice ? choice.toUpperCase() : '▶'}</Text>
    </Pressable>
  )
}

const withMemoizedExoticComponent = createFilterGenerator(
  ([name], _, exports) =>
    exports?.type?.length === 1 && exports.type.name === name,
  ([name]) => `memoizedExoticComponent(${name})`,
) as FilterGenerator<
  <P = object>(name: string) => Filter<{
    Result: MemoExoticComponent<FC<P>>
    Scopes: [typeof FilterScopes.Initialized]
  }>
>

export default plugin({
  start({ cleanup, logger, plugin: currentPlugin }) {
    stopped = false
    cleanup(() => { stopped = true })

    // Discord's in-app media keyboard. The button sits on Motion Photo thumbnails.
    cleanup(getModules(
      withMemoizedExoticComponent('MediaKeyboardItem'),
      MediaKeyboardItem => {
        cleanup(after(MediaKeyboardItem, 'type', node => {
          const individualCleanups: Array<() => void> = []
          // Keep hook order even when picker children are absent.
          useEffect(() => () => individualCleanups.forEach(fn => fn()))
          if (stopped) return node
          const element = node as ReactElement<{ children?: ReactNode }>
          if (!element || !element.props?.children) return node
          const children = Array.isArray(element.props.children)
            ? element.props.children
            : [element.props.children]

          for (const child of children) {
            if (!child || typeof child !== 'object' ||
                (child as any).type?.name !== 'MediaKeyboardImage') continue
            individualCleanups.push(instead(
              child as ReactElement<any, FC<MediaImageProps>>,
              'type',
              (args, original) => {
                const tree = Reflect.apply(original, undefined, args) as ReactElement<any>
                const imageUri = (args[0] as MediaImageProps)?.item?.node?.image?.uri
                if (stopped || !imageUri || !tree?.props) return tree
                tree.props.children = (
                  <View style={styles.wrapper}>
                    {tree.props.children}
                    <MotionPhotoBadge uri={imageUri} />
                  </View>
                )
                return tree
              },
            ))
          }
          return node
        }))
      },
    ))

    // Standard pre-upload path. A manually selected format avoids a second popup.
    cleanup(getModules(withProps('uploadLocalFiles'), mod => {
      logger.log('Motion Photo Sender: uploadLocalFiles found')
      cleanup(instead(mod, 'uploadLocalFiles', async function(args, original) {
        const payload = args[0] as UploadItem | undefined
        const items = payload?.items ?? payload?.files ?? payload?.uploads
        if (stopped || !Array.isArray(items)) {
          return Reflect.apply(original, this, args)
        }
        try {
          const kept: UploadItem[] = []
          for (const item of items as UploadItem[]) {
            if (stopped || !looksLikeJpeg(item)) {
              kept.push(item)
              continue
            }
            const uri = candidateUri(item)
            if (!uri) {
              kept.push(item)
              continue
            }
            const result = await callNativeMethod(
              'dev.motion.photo.process',
              [uri, formatFor(uri) ?? 'ask'],
            ) as ProcessingResult | null
            if (!result?.changed) {
              if (!result?.cancelled) kept.push(item)
              continue
            }
            replaceAsset(item, result)
            selectedFormats.delete(safeKey(uri))
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
          toast('Ошибка обработки Motion Photo. Отправляю оригинал.', true)
          return Reflect.apply(original, this, args)
        }
      }))
    }, { max: 1 }))
  },
})

declare module '@valenced/modules/native' {
  export interface NativeMethods {
    'dev.motion.photo.inspect': [
      args: [uri: string],
      returnValue: boolean,
    ]
    'dev.motion.photo.chooseFormat': [
      args: [],
      returnValue: Format | 'cancel',
    ]
    'dev.motion.photo.process': [
      args: [uri: string, mode: Format | 'ask'],
      returnValue: ProcessingResult | null,
    ]
    'dev.motion.photo.share': [
      args: [uri: string, mime: string, filename: string],
      returnValue: boolean,
    ]
  }
}
