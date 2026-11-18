import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";

/** Preguntas en la terminal, sin dependencias. */
export function prompter() {
  const rl = createInterface({ input: stdin, output: stdout });
  const ask = async (question, fallback = "") => {
    const answer = (await rl.question(fallback ? `${question} [${fallback}]: ` : `${question}: `)).trim();
    return answer || fallback;
  };
  const confirm = async (question, yes = true) => {
    const answer = (await rl.question(`${question} ${yes ? "[S/n]" : "[s/N]"}: `)).trim().toLowerCase();
    return answer ? answer.startsWith("s") || answer.startsWith("y") : yes;
  };
  /** Varias opciones por número («1,3,5»), con las marcadas por defecto. Devuelve los valores elegidos. */
  const choose = async (question, options, preselected) => {
    options.forEach((o, i) => console.log(`  ${String(i + 1).padStart(2)}. ${preselected.includes(o.value) ? "[x]" : "[ ]"} ${o.label}`));
    for (;;) {
      const answer = (await rl.question(`${question} (números separados por coma; Enter = los marcados): `)).trim();
      if (!answer) {
        if (preselected.length) return preselected;
        console.log("  Elige al menos uno.");
        continue;
      }
      const picked = [...new Set(answer.split(/[\s,]+/).map((n) => Number.parseInt(n, 10) - 1))];
      if (picked.length && picked.every((i) => i >= 0 && i < options.length)) return picked.map((i) => options[i].value);
      console.log("  No entendí: escribe los números de la lista, por ejemplo 1,3.");
    }
  };
  return { ask, confirm, choose, close: () => rl.close() };
}
