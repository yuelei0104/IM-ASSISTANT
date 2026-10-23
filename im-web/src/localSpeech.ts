export interface LocalSpeechResult {
  isFinal: boolean;
  [index: number]: { transcript: string };
}

export interface LocalSpeechEvent {
  results: ArrayLike<LocalSpeechResult>;
}

export interface LocalRecognizer {
  lang: string;
  processLocally: boolean;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: LocalSpeechEvent) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

export interface LocalSpeechConstructor {
  new (): LocalRecognizer;
  available(options: { langs: string[]; processLocally: true }): Promise<string>;
  install(options: { langs: string[]; processLocally: true }): Promise<boolean>;
}

const options = { langs: ["zh-CN"], processLocally: true as const };

export function localSpeechConstructor(browser: Window): LocalSpeechConstructor | null {
  const candidate = (browser as Window & {
    SpeechRecognition?: LocalSpeechConstructor;
    webkitSpeechRecognition?: LocalSpeechConstructor;
  }).SpeechRecognition || (browser as Window & {
    webkitSpeechRecognition?: LocalSpeechConstructor;
  }).webkitSpeechRecognition;
  if (!candidate || typeof candidate.available !== "function" ||
      typeof candidate.install !== "function") return null;
  return candidate;
}

export async function localSpeechAvailability(ctor: LocalSpeechConstructor): Promise<string> {
  return ctor.available(options);
}

export async function installLocalSpeech(ctor: LocalSpeechConstructor): Promise<boolean> {
  return ctor.install(options);
}

export function makeLocalRecognizer(ctor: LocalSpeechConstructor): LocalRecognizer | null {
  const recognition = new ctor();
  // A browser without the local-only flag must never receive microphone audio here.
  if (!("processLocally" in recognition)) return null;
  recognition.processLocally = true;
  recognition.lang = "zh-CN";
  recognition.continuous = true;
  recognition.interimResults = true;
  return recognition;
}

export function readSpeechResults(event: LocalSpeechEvent, seen: Set<number>): {
  final: string;
  interim: string;
} {
  const final: string[] = [];
  const interim: string[] = [];
  for (let i = 0; i < event.results.length; i++) {
    const result = event.results[i];
    const transcript = result?.[0]?.transcript?.trim();
    if (!transcript) continue;
    if (result.isFinal) {
      if (!seen.has(i)) {
        final.push(transcript);
        seen.add(i);
      }
    } else {
      interim.push(transcript);
    }
  }
  return { final: final.join(" "), interim: interim.join(" ") };
}
