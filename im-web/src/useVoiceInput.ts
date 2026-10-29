import { ref, type Ref } from "vue";
import {
  installLocalSpeech,
  localSpeechAvailability,
  localSpeechConstructor,
  makeLocalRecognizer,
  readSpeechResults,
  type LocalRecognizer,
  type LocalSpeechConstructor,
} from "./localSpeech";

type Deps = {
  page: () => string;
  canWrite: () => boolean;
  composer: Ref<string>;
  notify: (message: string) => void;
};

/**
 * Speech is transcribed locally and only ever becomes text the reader has to accept.
 * `version` guards every async hop: a stop, a conversation switch or a second start
 * must invalidate the callbacks of the recognizer that is still shutting down.
 */
export function useVoiceInput(deps: Deps) {
  const active = ref(false);
  const interim = ref("");
  const draft = ref("");
  const busy = ref(false);
  const installAvailable = ref(false);
  let version = 0;
  let recognition: LocalRecognizer | null = null;
  let restart: ReturnType<typeof setTimeout> | undefined;

  function stop(discard = true) {
    version++;
    active.value = false;
    busy.value = false;
    if (restart) clearTimeout(restart);
    restart = undefined;
    if (recognition) {
      const recognizer = recognition;
      recognition = null;
      recognizer.onend = null;
      recognizer.onerror = null;
      recognizer.onresult = null;
      try { recognizer.abort(); } catch { /* The browser may have ended it already. */ }
    }
    interim.value = "";
    if (discard) {
      draft.value = "";
      installAvailable.value = false;
    }
  }

  function begin(Ctor: LocalSpeechConstructor, requested: number) {
    if (requested !== version || deps.page() !== "chat" || !deps.canWrite() || document.hidden)
      return;
    let recognizer: LocalRecognizer | null;
    try { recognizer = makeLocalRecognizer(Ctor); }
    catch {
      deps.notify("无法启动本机语音识别，请改用文字输入");
      active.value = false;
      return;
    }
    if (!recognizer) {
      deps.notify("此浏览器无法保证本机转写，请改用文字输入");
      return;
    }
    const seen = new Set<number>();
    recognizer.onresult = (event) => {
      if (requested !== version || !deps.canWrite()) return;
      const result = readSpeechResults(event, seen);
      if (result.final)
        draft.value = [draft.value, result.final].filter(Boolean).join(" ").slice(0, 4000);
      interim.value = result.interim;
    };
    recognizer.onerror = (event) => {
      if (requested !== version) return;
      deps.notify(event.error === "not-allowed"
        ? "麦克风权限未开启" : "本机语音转写已停止，请检查语言包或麦克风");
      stop(false);
    };
    recognizer.onend = () => {
      if (recognition !== recognizer || requested !== version) return;
      recognition = null;
      interim.value = "";
      // Chrome ends a session on silence; restart so the reader can keep talking.
      if (active.value && !document.hidden && deps.canWrite())
        restart = setTimeout(() => begin(Ctor, requested), 350);
    };
    try {
      recognizer.start();
      recognition = recognizer;
      active.value = true;
    } catch {
      active.value = false;
      deps.notify("本机转写启动失败，请检查麦克风和语言包");
    }
  }

  async function toggle() {
    if (active.value) { stop(false); return; }
    if (busy.value || !deps.canWrite() || deps.page() !== "chat") return;
    const Ctor = localSpeechConstructor(window);
    if (!Ctor) {
      deps.notify("浏览器不支持本机语音识别，请改用文字输入");
      return;
    }
    const requested = ++version;
    busy.value = true;
    installAvailable.value = false;
    try {
      const status = await localSpeechAvailability(Ctor);
      if (requested !== version) return;
      if (status === "available") begin(Ctor, requested);
      else if (status === "downloadable") installAvailable.value = true;
      else deps.notify(status === "downloading"
        ? "中文本机语音包正在下载，请稍后重试" : "中文本机语音识别不可用，请改用文字输入");
    } catch {
      if (requested === version) deps.notify("无法检查本机语音识别能力，请改用文字输入");
    } finally {
      if (requested === version) busy.value = false;
    }
  }

  async function installPack() {
    const Ctor = localSpeechConstructor(window);
    if (!Ctor || !installAvailable.value || busy.value) return;
    const requested = ++version;
    busy.value = true;
    try {
      const installed = await installLocalSpeech(Ctor);
      if (requested !== version) return;
      installAvailable.value = false;
      if (installed && await localSpeechAvailability(Ctor) === "available") {
        if (requested === version) begin(Ctor, requested);
      } else deps.notify("中文语音包尚未就绪，请稍后重试");
    } catch {
      if (requested === version) deps.notify("中文语音包安装失败，请稍后重试");
    } finally {
      if (requested === version) busy.value = false;
    }
  }

  /** Move the transcript into the composer; the reader still decides to send it. */
  function accept() {
    if (!draft.value.trim() || !deps.canWrite()) return;
    stop(false);
    deps.composer.value = [deps.composer.value.trim(), draft.value.trim()].filter(Boolean).join(" ");
    draft.value = "";
    deps.notify("已填入输入框，请检查后点击发送");
  }

  return { active, interim, draft, busy, installAvailable, stop, toggle, installPack, accept };
}
