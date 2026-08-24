import { Section, SectionHead } from './Primitives'

const dim = 'text-ink-3-light dark:text-ink-3-dark'
const hot = 'text-accent-light dark:text-accent-dark'

export default function Install() {
  return (
    <Section id="install">
      <SectionHead eyebrow="Getting it running" title="Three commands">
        You need JDK 17, Android SDK 35, and a device with USB debugging on. Gradle
        comes with the repo.
      </SectionHead>

      <pre
        className="overflow-x-auto rounded-[10px] px-5 py-4 m-0
                   bg-raised-light dark:bg-raised-dark
                   border border-line-light dark:border-line-dark
                   font-mono text-[0.85rem] leading-[1.75]"
      >
<span className={dim}># 1 — get the source{'\n'}</span>
{'git clone https://github.com/kingofdead6/android-assisstant.git\ncd android-assisstant\n\n'}
<span className={dim}># 2 — point the build at your SDK (local.properties){'\n'}#     Windows uses forward slashes here too{'\n'}</span>
{'sdk.dir=C:/Users/you/AppData/Local/Android/Sdk\n\n'}
<span className={dim}># 3 — install to the connected phone{'\n'}</span>
{'./gradlew '}<span className={hot}>:app:installDebug</span>
      </pre>

      <p className="max-w-measure text-[0.95rem] text-ink-2-light dark:text-ink-2-dark">
        First build takes three to six minutes while Gradle fetches its dependencies;
        later ones take seconds. John then asks for <em>nothing</em> on launch — no
        wizard, no permission wall, no account. Each permission is requested the first
        time a feature genuinely needs it. The full README covers model setup, tokens
        and troubleshooting.
      </p>
    </Section>
  )
}
