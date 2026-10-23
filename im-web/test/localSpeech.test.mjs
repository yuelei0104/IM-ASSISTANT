import test from "node:test";
import assert from "node:assert/strict";
import {
  localSpeechConstructor, localSpeechAvailability, installLocalSpeech,
  makeLocalRecognizer, readSpeechResults,
} from "../src/localSpeech.ts";

test("unsupported remote-only recognition cannot start", () => {
  class RemoteRecognition { start() { throw new Error("must not start"); } }
  assert.equal(localSpeechConstructor({ SpeechRecognition: RemoteRecognition }), null);
  class FlaglessRecognition {
    static available = async () => "available";
    static install = async () => true;
  }
  assert.equal(makeLocalRecognizer(FlaglessRecognition), null);
});

test("checks local Chinese pack and forces local processing before starting", async () => {
  class Recognition {
    processLocally = false;
    static async available(options) {
      assert.deepEqual(options, { langs: ["zh-CN"], processLocally: true });
      return "downloadable";
    }
    static async install(options) {
      assert.deepEqual(options, { langs: ["zh-CN"], processLocally: true });
      return true;
    }
  }
  const ctor = localSpeechConstructor({ SpeechRecognition: Recognition });
  assert.equal(await localSpeechAvailability(ctor), "downloadable");
  assert.equal(await installLocalSpeech(ctor), true);
  const instance = makeLocalRecognizer(ctor);
  assert.equal(instance.processLocally, true);
  assert.equal(instance.lang, "zh-CN");
  assert.equal(instance.continuous, true);
});

test("ignores repeated final events and keeps changing interim separate", () => {
  const seen = new Set();
  const result = (text, isFinal) => ({ 0: { transcript: text }, isFinal });
  assert.deepEqual(readSpeechResults({ results: [result("你好", true), result("世", false)] }, seen),
    { final: "你好", interim: "世" });
  assert.deepEqual(readSpeechResults({ results: [result("你好", true), result("世界", true)] }, seen),
    { final: "世界", interim: "" });
});
