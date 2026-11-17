import { ToastActionCreators } from '@valenced/discord/actions'
import { getModules } from '@valenced/modules/finders'
import { createFilterGenerator } from '@valenced/modules/finders/filters'
import { callNativeMethod } from '@valenced/modules/native'
import { Dispatcher } from '@valenced/discord/common/flux'
import { onFluxEventDispatched } from '@valenced/discord/flux'
import { after, instead } from '@valenced/patcher'
import { useEffect, useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import type {
  Filter, FilterGenerator, FilterScopes,
} from '@valenced/modules/finders/filters'
import type { FC, MemoExoticComponent, ReactElement, ReactNode } from 'react'

type Format = 'gif' | 'photo'
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


/**
 * Dispatch a new upload descriptor rather than mutating a photo-specific object.
 */
function convertedAttachment(file: UploadItem, result: ProcessingResult): UploadItem {
  if (!result.uri || !result.filename || !result.mime) throw Error('Converted media metadata is incomplete')
  const id = String(file.id ?? file.filename ?? 'motion') + '-motion-' + Date.now()
  // Preserve the already working GIF upload path.
  const next: UploadItem = {
    ...file,
    id,
    uri: result.uri,
    originalUri: result.uri,
    filename: result.filename,
    mimeType: result.mime,
  }
  if ('file' in next) delete next.file
  if ('name' in next) next.name = result.filename
  if ('type' in next) next.type = result.mime
  if ('path' in next) next.path = result.uri.replace(/^file:\/\//, '')
  if ('size' in next && typeof result.bytes === 'number') next.size = result.bytes
  if (result.mime === 'image/gif') {
    delete next.width
    delete next.height
    delete next.playableDuration
  }
  return next
}

type UploadAction = {
  type: string
  files?: UploadItem[]
  channelId?: string
  draftType?: number
  motionPhotoSenderHandled?: boolean
  [key: string]: unknown
}

function selectedFor(file: UploadItem, numberOfImages: number): Format | undefined {
  const uri = candidateUri(file)
  const match = [uri, file.originalUri, file.contentUri, file.path].find(v =>
    typeof v === 'string' && selectedFormats.has(safeKey(v)),
  )
  if (typeof match === 'string') return selectedFormats.get(safeKey(match))
  // Gallery may substitute a temporary URI between picking and uploading.
  // Use fallback only if exactly one image and one chosen format exist.
  if (numberOfImages === 1 && selectedFormats.size === 1) {
    return selectedFormats.values().next().value
  }
}

async function convertUploadAction(action: UploadAction, selected: Map<number, Format>): Promise<void> {
  const files: UploadItem[] = []
  for (let i = 0; i < (action.files?.length ?? 0); i++) {
    const file = action.files![i]
    const format = selected.get(i)
    if (!format || format === 'photo') {
      files.push(file)
      continue
    }
    const uri = candidateUri(file)
    if (!uri) throw Error('Discord did not provide a Motion Photo URI.')
    toast('Converting Motion Photo to GIF...')
    const converted = await callNativeMethod('dev.motion.photo.process', [uri, format]) as ProcessingResult | null
    if (!converted?.changed) throw Error('The original Motion Photo is unavailable.')
    files.push(convertedAttachment(file, converted))
  }
  if (stopped) return
  // Preserve Discord's current chat and draft. Keep the verified GIF descriptor.
  Dispatcher.dispatch({ ...action, files, motionPhotoSenderHandled: true } as any)
  selectedFormats.clear()
  toast('Converted attachment is ready in Discord.')
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
      accessibilityLabel="Choose Motion Photo format"
      style={[styles.badge, choice ? styles.badgeChosen : null]}
      onPress={event => {
        event.stopPropagation()
        if (choice === 'gif') {
          selectedFormats.delete(key)
          setChoice(undefined)
          toast('GIF disabled. The original photo will be sent.')
        } else {
          selectedFormats.set(key, 'gif')
          setChoice('gif')
          toast('GIF selected. Select the photo and tap the Send button in Discord.')
        }
      }}
    >
      <Text style={styles.badgeText}>{choice ? 'GIF' : '▶'}</Text>
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

    // This event is dispatched by the Discord gallery's ordinary send button.
    // Pause the event, convert in Kotlin, then re-dispatch to the same draft.
    cleanup(onFluxEventDispatched('UPLOAD_ATTACHMENT_ADD_FILES', (event: any) => {
      const action = event as UploadAction
      if (stopped || action.motionPhotoSenderHandled || !Array.isArray(action.files)) return event
      const images = action.files.filter(file => looksLikeJpeg(file))
      if (!images.length || selectedFormats.size === 0) return event
      const selected = new Map<number, Format>()
      action.files.forEach((file, index) => {
        if (!looksLikeJpeg(file)) return
        const format = selectedFor(file, images.length)
        if (format) selected.set(index, format)
      })
      if (!selected.size) return event
      if ([...selected.values()].every(format => format === 'photo')) {
        selectedFormats.clear()
        return event
      }
      // Returning undefined synchronously cancels the ORIGINAL JPEG action.
      void convertUploadAction(action, selected).catch(error => {
        currentPlugin.reportError(error)
        const detail = error instanceof Error ? error.message : String(error)
        // Fail closed: do NOT upload the original instead.
        toast('Motion Photo: ' + detail + '. Upload cancelled.', true)
      })
      return undefined
    }))
  },
})

declare module '@valenced/modules/native' {
  export interface NativeMethods {
    'dev.motion.photo.inspect': [
      args: [uri: string],
      returnValue: boolean,
    ]
    'dev.motion.photo.process': [
      args: [uri: string, mode: Format | 'ask'],
      returnValue: ProcessingResult | null,
    ]
  }
}
