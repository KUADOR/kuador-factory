"use client";

import { Mic, MicOff } from "lucide-react";
import { useEffect, useRef, useState } from "react";

/** Lo mínimo del reconocimiento de voz del navegador (Chrome, Edge, Safari). */
interface Recognition {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> ; resultIndex: number }) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
  start: () => void;
  stop: () => void;
}

function recognitionClass(): (new () => Recognition) | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

/**
 * Dictar en vez de escribir (en el celular, en una reunión). Cada frase reconocida se agrega con {@code onText}. Si el
 * navegador no lo soporta, no se muestra.
 */
export function DictateButton({ onText, lang = "es-EC" }: { onText: (text: string) => void; lang?: string }) {
  const [supported, setSupported] = useState(false);
  const [listening, setListening] = useState(false);
  const rec = useRef<Recognition | null>(null);

  // eslint-disable-next-line react-hooks/set-state-in-effect -- se sabe recién en el navegador
  useEffect(() => setSupported(recognitionClass() !== null), []);
  useEffect(() => () => rec.current?.stop(), []);

  if (!supported) return null;

  const toggle = () => {
    if (listening) {
      rec.current?.stop();
      return;
    }
    const Klass = recognitionClass();
    if (!Klass) return;
    const r = new Klass();
    r.lang = lang;
    r.interimResults = false;
    r.continuous = true;
    r.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) onText(e.results[i][0].transcript.trim());
      }
    };
    r.onend = () => setListening(false);
    r.onerror = () => setListening(false);
    rec.current = r;
    r.start();
    setListening(true);
  };

  return (
    <button
      type="button"
      onClick={toggle}
      aria-pressed={listening}
      aria-label={listening ? "Dejar de dictar" : "Dictar"}
      title={listening ? "Dejar de dictar" : "Dictar"}
      className={`grid size-9 shrink-0 place-items-center rounded-md border ${listening ? "animate-pulse border-danger bg-danger/10 text-danger" : "border-line text-fg-muted hover:bg-hover hover:text-fg-strong"}`}
    >
      {listening ? <MicOff className="size-4" aria-hidden /> : <Mic className="size-4" aria-hidden />}
    </button>
  );
}
