import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { CSS2DObject, CSS2DRenderer } from "three/examples/jsm/renderers/CSS2DRenderer.js";

/** Lo que la escena necesita de cada puesto (sin depender de los tipos de la app). */
export interface SceneSeat {
  id: string;
  name: string;
  role: string;
  color: string;
  agent: boolean;
  /** Agente que trabaja en la PC de una persona: laptop en vez de monitor. */
  laptop: boolean;
  state: "working" | "asking" | "error" | "review" | "busy" | "queued" | "idle";
  screenKey: string | null;
  note: string;
  me: boolean;
  approvals: number;
}

export interface SceneZones {
  title: string;
  review: string[];
  inbox: number;
  archived: number;
  board: { name: string; n: number }[];
}

type Spot = string; // "desk:<id>" | "review" | "inbox" | "done"

interface DeskView {
  seat: SceneSeat;
  group: THREE.Group;
  body: THREE.Group;
  head: THREE.Object3D;
  armL: THREE.Object3D;
  armR: THREE.Object3D;
  led?: THREE.Mesh;
  screen: THREE.Mesh;
  screenCanvas: HTMLCanvasElement;
  screenTexture: THREE.CanvasTexture;
  label: CSS2DObject;
  lastScreenDraw: number;
  phase: number;
  desktop: THREE.Mesh;
}

const SKIN = ["#f2c7a5", "#e0ac84", "#c68b5e", "#9c6644", "#6f4a32", "#f6d9c3"];
const HAIR = ["#2b2118", "#5a3a22", "#a0662f", "#1b1b1b", "#c9a15a", "#7a2e1f"];

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return Math.abs(h);
}

const SPACING_X = 3.6;
const SPACING_Z = 3.4;

/**
 * La oficina en 3D (three.js): una habitación en diorama con un escritorio por puesto, personas y robots que se mueven
 * según su estado, pantallas con la tarjeta, sala de revisión de vidrio, bandeja, archivo y pizarra. Imperativa: React
 * solo le pasa los datos ({@link setData}) y le pide que vuele un papel ({@link fly}).
 */
export class OfficeScene {
  private readonly renderer: THREE.WebGLRenderer;
  private readonly labels: CSS2DRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera: THREE.PerspectiveCamera;
  private readonly controls: OrbitControls;
  private readonly clock = new THREE.Clock();
  private readonly room = new THREE.Group();
  private readonly desks = new Map<string, DeskView>();
  private readonly flights: { mesh: THREE.Mesh; from: THREE.Vector3; to: THREE.Vector3; start: number; target?: THREE.Object3D }[] = [];
  private readonly puffs: { mesh: THREE.Mesh; born: number; origin: THREE.Vector3 }[] = [];
  private zoneAnchors = new Map<Spot, THREE.Vector3>();
  private reviewGroup = new THREE.Group();
  private reviewSign: HTMLElement | null = null;
  private stackGroup = new THREE.Group();
  private boardCanvas = document.createElement("canvas");
  private boardTexture: THREE.CanvasTexture;
  private frame = 0;
  private paused = false;
  private lastInteraction = 0;
  private layoutKey = "";
  private resize: ResizeObserver;
  private down: { x: number; y: number } | null = null;

  constructor(
    private readonly host: HTMLElement,
    private readonly opts: { dark: boolean; reducedMotion: boolean; onSelect: (id: string) => void },
  ) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "high-performance" });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = window.innerWidth > 640;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = opts.dark ? 1.35 : 1.1;
    host.appendChild(this.renderer.domElement);
    this.renderer.domElement.style.display = "block";
    this.renderer.domElement.style.touchAction = "none";

    this.labels = new CSS2DRenderer();
    Object.assign(this.labels.domElement.style, { position: "absolute", inset: "0", pointerEvents: "none" });
    host.appendChild(this.labels.domElement);

    this.camera = new THREE.PerspectiveCamera(38, 1, 0.1, 200);
    this.camera.position.set(14, 15, 18);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.minPolarAngle = 0.35;
    this.controls.maxPolarAngle = 1.2;
    this.controls.minDistance = 6;
    this.controls.maxDistance = 60;
    this.controls.autoRotateSpeed = 0.35;
    this.controls.addEventListener("start", () => (this.lastInteraction = performance.now()));

    const bg = new THREE.Color(opts.dark ? "#0d1220" : "#e9eef6");
    this.scene.background = bg;
    this.scene.fog = new THREE.Fog(bg, 45, 90);
    this.lights();
    this.scene.add(this.room);

    this.boardCanvas.width = 512;
    this.boardCanvas.height = 300;
    this.boardTexture = new THREE.CanvasTexture(this.boardCanvas);
    this.boardTexture.colorSpace = THREE.SRGBColorSpace;

    this.renderer.domElement.addEventListener("pointerdown", this.onDown);
    this.renderer.domElement.addEventListener("pointerup", this.onUp);
    this.renderer.domElement.addEventListener("pointermove", this.onMove);
    this.resize = new ResizeObserver(() => this.fit());
    this.resize.observe(host);
    this.fit();
    this.loop();
  }

  // ---- API ----

  setData(seats: SceneSeat[], zones: SceneZones) {
    const key = seats.map((s) => s.id).join(",");
    if (key !== this.layoutKey) {
      this.layoutKey = key;
      this.build(seats);
    }
    for (const s of seats) {
      const d = this.desks.get(s.id);
      if (!d) continue;
      d.seat = s;
      this.drawScreen(d, performance.now(), true);
      this.updateLabel(d);
      this.updateDeskProps(d);
    }
    this.updateZones(zones);
    this.renderOnce();
  }

  /** Un papel con la clave de la tarjeta vuela de un lugar al otro, en arco. */
  fly(from: Spot, to: Spot, key: string) {
    const a = this.anchor(from);
    const b = this.anchor(to);
    if (!a || !b || this.opts.reducedMotion) return false;
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(0.55, 0.7), new THREE.MeshStandardMaterial({ map: paperTexture(key), side: THREE.DoubleSide, roughness: 0.8 }));
    mesh.castShadow = true;
    mesh.position.copy(a);
    this.scene.add(mesh);
    const target = to.startsWith("desk:") ? this.desks.get(to.slice(5))?.group : undefined;
    this.flights.push({ mesh, from: a.clone(), to: b.clone(), start: performance.now(), target });
    return true;
  }

  setPaused(paused: boolean) {
    this.paused = paused;
    if (!paused) {
      this.clock.getDelta();
      this.loop();
    }
  }

  setAutoRotate(on: boolean) {
    this.controls.autoRotate = on && !this.opts.reducedMotion;
  }

  dispose() {
    cancelAnimationFrame(this.frame);
    this.resize.disconnect();
    this.renderer.domElement.removeEventListener("pointerdown", this.onDown);
    this.renderer.domElement.removeEventListener("pointerup", this.onUp);
    this.renderer.domElement.removeEventListener("pointermove", this.onMove);
    this.controls.dispose();
    this.scene.traverse((o) => {
      const m = o as THREE.Mesh;
      m.geometry?.dispose?.();
      const mat = m.material as THREE.Material | THREE.Material[] | undefined;
      (Array.isArray(mat) ? mat : mat ? [mat] : []).forEach((x) => {
        (x as THREE.MeshStandardMaterial).map?.dispose();
        x.dispose();
      });
    });
    this.renderer.dispose();
    this.renderer.domElement.remove();
    this.labels.domElement.remove();
  }

  // ---- Construcción ----

  private lights() {
    const dark = this.opts.dark;
    this.scene.add(new THREE.HemisphereLight(dark ? "#b9c6ff" : "#ffffff", dark ? "#3a2f45" : "#b9a88f", dark ? 1.15 : 1.0));
    this.scene.add(new THREE.AmbientLight(dark ? "#ffe2b8" : "#ffffff", dark ? 0.35 : 0.2));
    const sun = new THREE.DirectionalLight(dark ? "#ffe9c8" : "#fff4e0", dark ? 1.25 : 1.8);
    sun.position.set(12, 22, 10);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    sun.shadow.camera.left = -30;
    sun.shadow.camera.right = 30;
    sun.shadow.camera.top = 30;
    sun.shadow.camera.bottom = -30;
    sun.shadow.bias = -0.0005;
    this.scene.add(sun);
  }

  private build(seats: SceneSeat[]) {
    // Vuelve a armar la habitación cuando cambia quién está en el equipo.
    this.room.clear();
    for (const d of this.desks.values()) d.label.removeFromParent();
    this.desks.clear();
    this.zoneAnchors.clear();

    const cols = Math.max(2, Math.ceil(Math.sqrt(seats.length * 1.4)));
    const rows = Math.max(1, Math.ceil(seats.length / cols));
    const deskW = cols * SPACING_X;
    const deskD = rows * SPACING_Z;
    const width = deskW + 6.5; // la sala de revisión a la derecha
    const depth = deskD + 7; // pizarra al fondo y bandeja/archivo adelante
    const x0 = -width / 2 + 2.6;
    const z0 = -depth / 2 + 4.2;

    // Piso de madera y paredes en L (diorama).
    const floor = new THREE.Mesh(new THREE.BoxGeometry(width, 0.4, depth), new THREE.MeshStandardMaterial({ map: woodTexture(this.opts.dark), roughness: 0.85 }));
    floor.position.y = -0.2;
    floor.receiveShadow = true;
    this.room.add(floor);
    const wallMat = new THREE.MeshStandardMaterial({ color: this.opts.dark ? "#3a4566" : "#f4f1ea", roughness: 0.95 });
    const back = new THREE.Mesh(new THREE.BoxGeometry(width, 4.2, 0.3), wallMat);
    back.position.set(0, 2.1, -depth / 2 - 0.15);
    back.receiveShadow = true;
    const left = new THREE.Mesh(new THREE.BoxGeometry(0.3, 4.2, depth), wallMat);
    left.position.set(-width / 2 - 0.15, 2.1, 0);
    left.receiveShadow = true;
    this.room.add(back, left);
    // Ventanas con luz (de día cielo, de noche ciudad).
    for (let i = 0; i < 3; i++) {
      const win = new THREE.Mesh(new THREE.PlaneGeometry(2.2, 1.6), new THREE.MeshBasicMaterial({ map: windowTexture(this.opts.dark, i) }));
      win.rotation.y = Math.PI / 2;
      win.position.set(-width / 2 + 0.01, 2.4, -depth / 2 + 3 + i * 3.4);
      if (win.position.z < depth / 2 - 1) this.room.add(win);
    }

    // Pizarra en la pared del fondo.
    const board = new THREE.Mesh(new THREE.PlaneGeometry(5.2, 3), new THREE.MeshStandardMaterial({ map: this.boardTexture, roughness: 0.6 }));
    board.position.set(x0 + deskW / 2 - SPACING_X / 2, 2.3, -depth / 2 + 0.02);
    const frame = new THREE.Mesh(new THREE.BoxGeometry(5.4, 3.2, 0.06), new THREE.MeshStandardMaterial({ color: "#8b8f99", metalness: 0.4, roughness: 0.4 }));
    frame.position.copy(board.position).add(new THREE.Vector3(0, 0, -0.04));
    this.room.add(frame, board);

    // Escritorios.
    seats.forEach((s, i) => {
      const col = i % cols;
      const row = Math.floor(i / cols);
      const d = this.desk(s);
      d.group.position.set(x0 + col * SPACING_X, 0, z0 + row * SPACING_Z);
      this.room.add(d.group);
      this.desks.set(s.id, d);
    });

    // Sala de revisión de vidrio (derecha), bandeja y archivo (adelante a la izquierda).
    const rx = width / 2 - 2.4;
    const rz = -depth / 2 + 2.6;
    this.reviewRoom(rx, rz);
    this.zoneAnchors.set("review", new THREE.Vector3(rx, 1.1, rz));
    this.stackGroup = new THREE.Group();
    this.stackGroup.position.set(-width / 2 + 1.6, 0, depth / 2 - 1.6);
    this.room.add(this.stackGroup);
    this.zoneAnchors.set("inbox", new THREE.Vector3(-width / 2 + 1.2, 1.0, depth / 2 - 1.6));
    this.zoneAnchors.set("done", new THREE.Vector3(-width / 2 + 3.2, 1.4, depth / 2 - 1.6));
    this.room.add(plant(width / 2 - 0.8, depth / 2 - 0.8, 1.3), plant(-width / 2 + 0.7, -depth / 2 + 0.7, 1.5), plant(width / 2 - 0.8, -depth / 2 + 0.8, 1.1));

    // Lámparas de techo cálidas sobre las filas de escritorios (de noche dan el ambiente).
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c += 2) {
        const bulb = new THREE.PointLight(this.opts.dark ? "#ffd29a" : "#fff1dc", this.opts.dark ? 9 : 3, 9, 1.6);
        bulb.position.set(x0 + (c + 0.5) * SPACING_X, 3.9, z0 + r * SPACING_Z - 1.7);
        const shade = new THREE.Mesh(new THREE.CylinderGeometry(0.18, 0.4, 0.25, 20, 1, true), new THREE.MeshStandardMaterial({ color: "#f3e3c8", emissive: "#ffcf8a", emissiveIntensity: this.opts.dark ? 0.8 : 0.2, side: THREE.DoubleSide }));
        shade.position.copy(bulb.position).add(new THREE.Vector3(0, 0.15, 0));
        const cord = new THREE.Mesh(new THREE.CylinderGeometry(0.01, 0.01, 1.2, 4), new THREE.MeshBasicMaterial({ color: "#444" }));
        cord.position.copy(bulb.position).add(new THREE.Vector3(0, 0.85, 0));
        this.room.add(bulb, shade, cord);
      }
    }

    // Encuadre según el tamaño de la oficina: cerca, para ver a cada uno.
    const span = Math.max(width, depth);
    this.camera.position.set(span * 0.42, span * 0.5, span * 0.68);
    this.controls.target.set(0, 0.8, 0.3);
    this.controls.maxDistance = span * 2.2;
    this.controls.update();
  }

  private desk(s: SceneSeat): DeskView {
    const h = hash(s.id);
    const group = new THREE.Group();
    group.userData.seatId = s.id;
    const wood = new THREE.MeshStandardMaterial({ color: "#b98552", roughness: 0.7 });
    const dark = new THREE.MeshStandardMaterial({ color: "#2b2f3a", roughness: 0.5, metalness: 0.3 });

    // Alfombra con el color del puesto.
    const rug = new THREE.Mesh(new THREE.CylinderGeometry(1.45, 1.45, 0.02, 40), new THREE.MeshStandardMaterial({ color: s.color, roughness: 1, transparent: true, opacity: 0.22 }));
    rug.position.set(0, 0.01, -0.3);
    rug.receiveShadow = true;
    group.add(rug);

    const desktop = new THREE.Mesh(new THREE.BoxGeometry(2.1, 0.08, 1.0), wood);
    desktop.position.set(0, 0.76, -0.1);
    desktop.castShadow = true;
    desktop.receiveShadow = true;
    group.add(desktop);
    for (const [x, z] of [[-0.98, -0.53], [0.98, -0.53], [-0.98, 0.33], [0.98, 0.33]]) {
      const leg = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.76, 0.07), dark);
      leg.position.set(x, 0.38, z - 0.0);
      leg.castShadow = true;
      group.add(leg);
    }

    // Monitor en la esquina izquierda, girado hacia la persona (y hacia la cámara); laptop si es agente local.
    const screenCanvas = document.createElement("canvas");
    screenCanvas.width = 256;
    screenCanvas.height = 160;
    const screenTexture = new THREE.CanvasTexture(screenCanvas);
    screenTexture.colorSpace = THREE.SRGBColorSpace;
    const screenMat = new THREE.MeshBasicMaterial({ map: screenTexture });
    let screen: THREE.Mesh;
    if (s.laptop) {
      const base = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.03, 0.42), dark);
      base.position.set(-0.6, 0.815, -0.05);
      base.rotation.y = 0.55;
      const lid = new THREE.Group();
      lid.position.set(-0.72, 0.83, -0.22);
      lid.rotation.y = 0.55;
      const lidBack = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.4, 0.02), dark);
      lidBack.position.y = 0.2;
      screen = new THREE.Mesh(new THREE.PlaneGeometry(0.56, 0.35), screenMat);
      screen.position.set(0, 0.2, 0.012);
      lid.add(lidBack, screen);
      lid.rotation.x = -0.25;
      base.castShadow = true;
      group.add(base, lid);
    } else {
      const monitor = new THREE.Group();
      monitor.position.set(-0.68, 0.8, -0.3);
      monitor.rotation.y = 0.6;
      const stand = new THREE.Mesh(new THREE.CylinderGeometry(0.03, 0.03, 0.3, 10), dark);
      stand.position.y = 0.15;
      const foot = new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.18, 0.03, 20), dark);
      const panel = new THREE.Mesh(new THREE.BoxGeometry(0.92, 0.58, 0.05), dark);
      panel.position.y = 0.58;
      panel.castShadow = true;
      screen = new THREE.Mesh(new THREE.PlaneGeometry(0.84, 0.5), screenMat);
      screen.position.set(0, 0.58, 0.027);
      monitor.add(stand, foot, panel, screen);
      group.add(monitor);
    }
    const keyboard = new THREE.Mesh(new THREE.BoxGeometry(0.6, 0.025, 0.2), new THREE.MeshStandardMaterial({ color: "#d9dce3", roughness: 0.6 }));
    keyboard.position.set(0.05, 0.815, -0.3);
    keyboard.rotation.y = 0.2;
    group.add(keyboard);

    // Taza y decoración propia del puesto.
    const mug = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.06, 0.14, 14), new THREE.MeshStandardMaterial({ color: ["#ffd66b", "#ff8a4c", "#8fb4ff", "#ffffff"][h % 4], roughness: 0.4 }));
    mug.position.set(0.75, 0.87, 0.1);
    mug.userData.mug = true;
    group.add(mug);
    const decor = h % 4;
    if (decor === 0) group.add(plant(0.82, -0.4, 0.5, 0.8));
    if (decor === 1) group.add(lamp(0.8, -0.4));
    if (decor === 2) group.add(books(0.72, -0.4));

    // Silla.
    const chair = new THREE.Group();
    chair.position.set(0.1, 0, -0.95);
    const seat = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.08, 0.58), new THREE.MeshStandardMaterial({ color: "#3a3f4d", roughness: 0.8 }));
    seat.position.y = 0.48;
    const backrest = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.7, 0.08), new THREE.MeshStandardMaterial({ color: "#3a3f4d", roughness: 0.8 }));
    backrest.position.set(0, 0.88, -0.28);
    const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.035, 0.42, 8), dark);
    pole.position.y = 0.24;
    seat.castShadow = backrest.castShadow = true;
    chair.add(seat, backrest, pole);
    group.add(chair);

    // Persona o robot, mirando a la cámara.
    const body = new THREE.Group();
    body.position.set(0.1, 0.52, -0.8);
    const color = new THREE.Color(s.color);
    const clothes = new THREE.MeshStandardMaterial({ color, roughness: 0.6 });
    let head: THREE.Object3D;
    let led: THREE.Mesh | undefined;
    if (s.agent) {
      const torso = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.5, 0.36), clothes);
      torso.position.y = 0.32;
      torso.castShadow = true;
      const chest = new THREE.Mesh(new THREE.PlaneGeometry(0.22, 0.12), new THREE.MeshBasicMaterial({ color: "#ffffff", transparent: true, opacity: 0.55 }));
      chest.position.set(0, 0.36, 0.181);
      const headG = new THREE.Group();
      headG.position.y = 0.8;
      const skull = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.38, 0.38), new THREE.MeshStandardMaterial({ color: "#c9cfdb", roughness: 0.35, metalness: 0.35 }));
      skull.castShadow = true;
      const visor = new THREE.Mesh(new THREE.PlaneGeometry(0.36, 0.15), new THREE.MeshBasicMaterial({ color: "#0f1626" }));
      visor.position.set(0, 0.02, 0.191);
      const eyeMat = new THREE.MeshBasicMaterial({ color });
      const eyeL = new THREE.Mesh(new THREE.SphereGeometry(0.035, 12, 12), eyeMat);
      eyeL.position.set(-0.08, 0.02, 0.2);
      const eyeR = eyeL.clone();
      eyeR.position.x = 0.08;
      const antenna = new THREE.Mesh(new THREE.CylinderGeometry(0.012, 0.012, 0.18, 6), new THREE.MeshStandardMaterial({ color: "#8b8f99" }));
      antenna.position.y = 0.28;
      led = new THREE.Mesh(new THREE.SphereGeometry(0.045, 12, 12), new THREE.MeshBasicMaterial({ color }));
      led.position.y = 0.39;
      headG.add(skull, visor, eyeL, eyeR, antenna, led);
      body.add(torso, chest, headG);
      head = headG;
    } else {
      const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.22, 0.3, 6, 16), clothes);
      torso.position.y = 0.36;
      torso.castShadow = true;
      const headG = new THREE.Group();
      headG.position.y = 0.86;
      const skin = new THREE.MeshStandardMaterial({ color: SKIN[h % SKIN.length], roughness: 0.7 });
      const face = new THREE.Mesh(new THREE.SphereGeometry(0.2, 24, 24), skin);
      face.castShadow = true;
      const hair = new THREE.Mesh(new THREE.SphereGeometry(0.212, 24, 24, 0, Math.PI * 2, 0, Math.PI * ((h >> 3) % 2 ? 0.5 : 0.62)), new THREE.MeshStandardMaterial({ color: HAIR[(h >> 5) % HAIR.length], roughness: 0.9 }));
      hair.rotation.x = -0.25;
      const eyeMat = new THREE.MeshBasicMaterial({ color: "#1b1b1b" });
      const eyeL = new THREE.Mesh(new THREE.SphereGeometry(0.022, 10, 10), eyeMat);
      eyeL.position.set(-0.07, 0.02, 0.185);
      const eyeR = eyeL.clone();
      eyeR.position.x = 0.07;
      headG.add(face, hair, eyeL, eyeR);
      body.add(torso, headG);
      head = headG;
      // Mira a la cámara: la cara hacia +z.
    }
    const armMat = clothes;
    const armL = new THREE.Group();
    const armR = new THREE.Group();
    for (const [arm, x] of [[armL, -0.27], [armR, 0.27]] as const) {
      arm.position.set(x, 0.5, 0);
      const limb = new THREE.Mesh(new THREE.CapsuleGeometry(0.06, 0.32, 4, 10), armMat);
      limb.rotation.x = Math.PI / 2.4;
      limb.position.set(0, -0.1, 0.17);
      limb.castShadow = true;
      arm.add(limb);
      body.add(arm);
    }
    body.scale.setScalar(1.35);
    group.add(body);

    // Etiqueta HTML sobre la cabeza (nombre, rol, estado).
    const el = document.createElement("div");
    el.className = "office3d-label";
    const label = new CSS2DObject(el);
    label.position.set(0.1, 2.75, -0.8);
    group.add(label);
    el.addEventListener("click", () => this.opts.onSelect(s.id));

    const view: DeskView = { seat: s, group, body, head, armL, armR, led, screen, screenCanvas, screenTexture, label, lastScreenDraw: 0, phase: (h % 1000) / 159, desktop };
    group.traverse((o) => (o.userData.seatId = s.id));
    return view;
  }

  private reviewRoom(x: number, z: number) {
    const g = new THREE.Group();
    g.position.set(x, 0, z);
    const glass = new THREE.MeshStandardMaterial({ color: "#9fd3ff", transparent: true, opacity: 0.16, roughness: 0.1, metalness: 0.1, depthWrite: false });
    const frameMat = new THREE.MeshStandardMaterial({ color: "#8b8f99", metalness: 0.5, roughness: 0.35 });
    const w = 3.6;
    const d = 3.4;
    for (const [px, pz, sx, sz] of [[0, -d / 2, w, 0.05], [-w / 2, 0, 0.05, d], [w / 2, 0, 0.05, d], [w / 4 + 0.4, d / 2, w / 2 - 0.8, 0.05]]) {
      const pane = new THREE.Mesh(new THREE.BoxGeometry(sx, 2.4, sz), glass);
      pane.position.set(px, 1.2, pz);
      g.add(pane);
      const top = new THREE.Mesh(new THREE.BoxGeometry(sx + 0.05, 0.06, sz + 0.05), frameMat);
      top.position.set(px, 2.42, pz);
      g.add(top);
    }
    const table = new THREE.Mesh(new THREE.CylinderGeometry(0.85, 0.85, 0.06, 40), new THREE.MeshStandardMaterial({ color: "#f3efe6", roughness: 0.5 }));
    table.position.y = 0.78;
    table.castShadow = table.receiveShadow = true;
    const foot = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.3, 0.78, 16), frameMat);
    foot.position.y = 0.39;
    g.add(table, foot);
    const sign = document.createElement("div");
    sign.className = "office3d-sign";
    sign.textContent = "🖋️ Sala de revisión";
    this.reviewSign = sign;
    const signObj = new CSS2DObject(sign);
    signObj.position.set(0, 2.9, 0);
    g.add(signObj);
    sign.classList.add("office3d-sign-action");
    sign.addEventListener("click", () => this.opts.onSelect("zone:review"));
    // La mesa abre lo que espera aprobación; cada papel sobre ella abre su tarjeta (el vidrio no tapa los clics).
    table.userData.seatId = foot.userData.seatId = "zone:review";
    this.reviewGroup = new THREE.Group();
    this.reviewGroup.position.y = 0.82;
    g.add(this.reviewGroup);
    this.room.add(g);
  }

  // ---- Actualización de datos ----

  private updateLabel(d: DeskView) {
    const s = d.seat;
    const el = d.label.element;
    const bubble = s.state === "asking" ? "❓" : s.state === "error" ? "⚠️" : s.state === "review" ? `🖋️ ${s.approvals}` : s.state === "working" ? "💻" : "";
    el.innerHTML = "";
    el.dataset.state = s.state;
    if (bubble) {
      const b = document.createElement("span");
      b.className = "office3d-bubble";
      b.textContent = bubble;
      el.appendChild(b);
    }
    const name = document.createElement("span");
    name.className = "office3d-name";
    name.textContent = s.name + (s.me ? " · tú" : "");
    const note = document.createElement("span");
    note.className = "office3d-note";
    note.textContent = s.note;
    el.append(name, note);
  }

  private updateDeskProps(d: DeskView) {
    const mat = d.desktop.material as THREE.MeshStandardMaterial;
    mat.emissive = new THREE.Color(d.seat.state === "error" ? "#5a1010" : "#000000");
  }

  private updateZones(z: SceneZones) {
    // Papeles sobre la mesa de revisión.
    if (this.reviewSign) this.reviewSign.textContent = z.review.length > 0 ? `🖋️ Sala de revisión · ${z.review.length} por aprobar` : "🖋️ Sala de revisión";
    this.reviewGroup.clear();
    z.review.slice(0, 12).forEach((key, i) => {
      const p = new THREE.Mesh(new THREE.PlaneGeometry(0.42, 0.54), new THREE.MeshStandardMaterial({ map: paperTexture(key), side: THREE.DoubleSide }));
      const a = (i / Math.max(1, Math.min(12, z.review.length))) * Math.PI * 2;
      p.rotation.x = -Math.PI / 2;
      p.rotation.z = a;
      p.position.set(Math.cos(a) * 0.55, 0.005 + i * 0.002, Math.sin(a) * 0.55);
      p.userData.seatId = `card:${key}`;
      this.reviewGroup.add(p);
    });
    // Bandeja (papeles apilados) y archivo (libros).
    this.stackGroup.clear();
    const tray = new THREE.Mesh(new THREE.BoxGeometry(0.9, 0.7, 0.7), new THREE.MeshStandardMaterial({ color: "#7a5233", roughness: 0.8 }));
    tray.position.set(-0.4, 0.35, 0);
    tray.castShadow = true;
    tray.userData.seatId = "zone:inbox";
    this.stackGroup.add(tray);
    for (let i = 0; i < Math.min(z.inbox, 14); i++) {
      const sheet = new THREE.Mesh(new THREE.BoxGeometry(0.55, 0.02, 0.42), new THREE.MeshStandardMaterial({ color: "#fffdf5" }));
      sheet.position.set(-0.4 + (Math.random() - 0.5) * 0.05, 0.72 + i * 0.025, (Math.random() - 0.5) * 0.05);
      sheet.rotation.y = (Math.random() - 0.5) * 0.3;
      sheet.userData.seatId = "zone:inbox";
      this.stackGroup.add(sheet);
    }
    const shelf = new THREE.Mesh(new THREE.BoxGeometry(1.8, 1.4, 0.5), new THREE.MeshStandardMaterial({ color: "#8a5f3b", roughness: 0.8 }));
    shelf.position.set(1.6, 0.7, 0);
    shelf.castShadow = true;
    shelf.userData.seatId = "zone:done";
    this.stackGroup.add(shelf);
    const colors = ["#8fb4ff", "#c4a7ff", "#34d399", "#ff8a4c", "#ff6b78", "#ffd66b"];
    for (let i = 0; i < Math.min(z.archived, 24); i++) {
      const book = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.36 + (i % 3) * 0.04, 0.32), new THREE.MeshStandardMaterial({ color: colors[i % colors.length] }));
      const row = Math.floor(i / 12);
      book.position.set(0.85 + (i % 12) * 0.125, 1.6 + row * -0.6 - 0.62, 0.05);
      book.userData.seatId = "zone:done";
      this.stackGroup.add(book);
    }
    const tags: [string, number, number, string][] = [[`📥 Bandeja · ${z.inbox}`, -0.6, 1.25, "zone:inbox"], [`🗄️ Archivo · ${z.archived}`, 1.9, 2.0, "zone:done"]];
    for (const [text, x, y, zone] of tags) {
      const el = document.createElement("div");
      el.className = "office3d-sign office3d-sign-action";
      el.textContent = text;
      el.addEventListener("click", () => this.opts.onSelect(zone));
      const o = new CSS2DObject(el);
      o.position.set(x, y, 0);
      this.stackGroup.add(o);
    }
    drawBoard(this.boardCanvas, z, this.opts.dark);
    this.boardTexture.needsUpdate = true;
  }

  private drawScreen(d: DeskView, now: number, force = false) {
    if (!force && now - d.lastScreenDraw < 450) return;
    d.lastScreenDraw = now;
    const c = d.screenCanvas.getContext("2d")!;
    const { width: w, height: h } = d.screenCanvas;
    const s = d.seat;
    const on = s.state === "working" || s.state === "error";
    c.fillStyle = s.state === "error" ? "#3a0c10" : on ? "#0b1424" : s.state === "idle" ? "#141a26" : "#18202f";
    c.fillRect(0, 0, w, h);
    if (on || s.screenKey) {
      c.fillStyle = s.state === "error" ? "#ff8b8b" : "#cfe0ff";
      c.font = "bold 30px monospace";
      c.fillText(s.screenKey ?? "", 14, 38);
    }
    if (s.state === "working") {
      const t = Math.floor(now / 450);
      for (let i = 0; i < 6; i++) {
        const len = 40 + ((i * 53 + t * 37) % 150);
        c.fillStyle = ["#7ee2a8", "#8fb4ff", "#ffd66b", "#c4a7ff"][(i + t) % 4];
        c.fillRect(14 + (i % 2) * 16, 58 + i * 15, len, 7);
      }
    } else if (s.state === "error") {
      c.fillStyle = "#ff6b78";
      c.font = "bold 46px sans-serif";
      c.fillText("⚠", w / 2 - 20, h / 2 + 34);
    } else if (s.state === "idle") {
      const t = now / 2000;
      c.fillStyle = "#2c3a58";
      c.beginPath();
      c.arc(w / 2 + Math.cos(t) * 60, h / 2 + Math.sin(t * 1.3) * 30, 12, 0, Math.PI * 2);
      c.fill();
    } else {
      c.fillStyle = "#3b4a6b";
      for (let i = 0; i < 3; i++) c.fillRect(14, 62 + i * 18, 120 - i * 25, 8);
    }
    d.screenTexture.needsUpdate = true;
  }

  // ---- Animación ----

  private loop = () => {
    if (this.paused) return;
    this.frame = requestAnimationFrame(this.loop);
    const now = performance.now();
    const t = this.clock.getElapsedTime();
    if (!this.opts.reducedMotion) {
      this.controls.autoRotate = this.controls.autoRotate && now - this.lastInteraction > 8000;
      for (const d of this.desks.values()) this.animateDesk(d, t, now);
      this.animateFlights(now);
      this.animatePuffs(now);
    }
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
    this.labels.render(this.scene, this.camera);
  };

  private renderOnce() {
    this.renderer.render(this.scene, this.camera);
    this.labels.render(this.scene, this.camera);
  }

  private animateDesk(d: DeskView, t: number, now: number) {
    const s = d.seat;
    const p = d.phase;
    const agentFloat = d.seat.agent ? Math.sin(t * 2 + p) * 0.03 : 0;
    d.body.position.y = 0.52 + agentFloat + Math.sin(t * 1.6 + p) * 0.008;
    d.armL.rotation.x = 0;
    d.armR.rotation.x = 0;
    if (s.state === "working") {
      d.armL.rotation.x = Math.sin(t * 18 + p) * 0.18;
      d.armR.rotation.x = Math.sin(t * 18 + p + Math.PI) * 0.18;
      d.head.rotation.y = -0.45 + Math.sin(t * 0.8 + p) * 0.05;
      d.head.rotation.x = 0.12;
      this.drawScreen(d, now);
    } else if (s.state === "asking") {
      // Levanta la mano y la agita: tiene una pregunta.
      d.armR.rotation.x = -2.2 + Math.sin(t * 7 + p) * 0.25;
      d.head.rotation.y = Math.sin(t * 1.2 + p) * 0.3;
    } else if (s.state === "error") {
      d.head.rotation.x = 0.35;
      d.head.rotation.y = Math.sin(t * 3 + p) * 0.15;
      this.drawScreen(d, now);
      if (Math.random() < 0.06) this.puff(d);
    } else if (s.state === "idle") {
      d.head.rotation.y = Math.sin(t * 0.5 + p) * 0.5;
      d.head.rotation.x = Math.sin(t * 0.7 + p) * 0.05;
      d.armL.rotation.x = Math.max(0, Math.sin(t * 0.6 + p)) * -0.9; // toma café
      this.drawScreen(d, now);
    } else {
      d.head.rotation.y = Math.sin(t * 0.6 + p) * 0.25;
      d.head.rotation.x = 0.05;
    }
    if (d.led) {
      const blink = s.state === "working" || s.state === "asking" ? (Math.sin(t * 8) > 0 ? 1 : 0.2) : 1;
      (d.led.material as THREE.MeshBasicMaterial).opacity = blink;
      (d.led.material as THREE.MeshBasicMaterial).transparent = true;
    }
  }

  private puff(d: DeskView) {
    const origin = new THREE.Vector3();
    d.screen.getWorldPosition(origin);
    origin.y += 0.35;
    const mesh = new THREE.Mesh(new THREE.SphereGeometry(0.09, 8, 8), new THREE.MeshStandardMaterial({ color: "#6b6f7a", transparent: true, opacity: 0.6 }));
    mesh.position.copy(origin);
    this.scene.add(mesh);
    this.puffs.push({ mesh, born: performance.now(), origin });
  }

  private animatePuffs(now: number) {
    for (let i = this.puffs.length - 1; i >= 0; i--) {
      const p = this.puffs[i];
      const k = (now - p.born) / 1800;
      if (k >= 1) {
        p.mesh.removeFromParent();
        p.mesh.geometry.dispose();
        (p.mesh.material as THREE.Material).dispose();
        this.puffs.splice(i, 1);
        continue;
      }
      p.mesh.position.set(p.origin.x + Math.sin(k * 6) * 0.1, p.origin.y + k * 1.2, p.origin.z);
      p.mesh.scale.setScalar(1 + k * 2.5);
      (p.mesh.material as THREE.MeshStandardMaterial).opacity = 0.6 * (1 - k);
    }
  }

  private animateFlights(now: number) {
    for (let i = this.flights.length - 1; i >= 0; i--) {
      const f = this.flights[i];
      const k = Math.min(1, (now - f.start) / 1600);
      const e = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
      const lift = 2 + f.from.distanceTo(f.to) * 0.25;
      f.mesh.position.lerpVectors(f.from, f.to, e);
      f.mesh.position.y += Math.sin(Math.PI * e) * lift;
      f.mesh.rotation.y = e * Math.PI * 4;
      f.mesh.rotation.x = Math.sin(e * Math.PI) * 0.6;
      f.mesh.lookAt(this.camera.position.x, f.mesh.position.y, this.camera.position.z);
      f.mesh.rotateZ(Math.sin(e * Math.PI * 3) * 0.4);
      if (k >= 1) {
        f.mesh.removeFromParent();
        f.mesh.geometry.dispose();
        (f.mesh.material as THREE.MeshStandardMaterial).map?.dispose();
        (f.mesh.material as THREE.Material).dispose();
        this.flights.splice(i, 1);
        if (f.target) bounce(f.target);
      }
    }
  }

  private anchor(spot: Spot): THREE.Vector3 | undefined {
    if (spot.startsWith("desk:")) {
      const d = this.desks.get(spot.slice(5));
      if (!d) return undefined;
      return d.group.position.clone().add(new THREE.Vector3(0, 1.3, -0.3));
    }
    return this.zoneAnchors.get(spot)?.clone();
  }

  // ---- Interacción ----

  private onDown = (e: PointerEvent) => {
    this.down = { x: e.clientX, y: e.clientY };
    this.lastInteraction = performance.now();
  };

  private onUp = (e: PointerEvent) => {
    if (!this.down || Math.hypot(e.clientX - this.down.x, e.clientY - this.down.y) > 6) return;
    const id = this.pick(e);
    if (id) this.opts.onSelect(id);
  };

  private onMove = (e: PointerEvent) => {
    if (e.buttons) return;
    this.renderer.domElement.style.cursor = this.pick(e) ? "pointer" : "grab";
  };

  private pick(e: PointerEvent): string | null {
    const rect = this.renderer.domElement.getBoundingClientRect();
    const pointer = new THREE.Vector2(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    const ray = new THREE.Raycaster();
    ray.setFromCamera(pointer, this.camera);
    const hit = ray.intersectObjects(this.room.children, true).find((h) => h.object.userData.seatId);
    return hit ? (hit.object.userData.seatId as string) : null;
  }

  private fit() {
    const w = this.host.clientWidth || 1;
    const h = this.host.clientHeight || 1;
    this.renderer.setSize(w, h);
    this.labels.setSize(w, h);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderOnce();
  }
}

// ---- Piezas y texturas ----

function bounce(o: THREE.Object3D) {
  const start = performance.now();
  const step = () => {
    const k = (performance.now() - start) / 400;
    o.scale.setScalar(1 + Math.sin(Math.min(1, k) * Math.PI) * 0.06);
    if (k < 1) requestAnimationFrame(step);
    else o.scale.setScalar(1);
  };
  step();
}

function plant(x: number, z: number, height: number, scale = 1): THREE.Group {
  const g = new THREE.Group();
  g.position.set(x, scale < 1 ? 0.8 : 0, z);
  g.scale.setScalar(scale);
  const pot = new THREE.Mesh(new THREE.CylinderGeometry(0.22, 0.17, 0.4, 16), new THREE.MeshStandardMaterial({ color: "#b8643e", roughness: 0.8 }));
  pot.position.y = 0.2;
  pot.castShadow = true;
  g.add(pot);
  const leafMat = new THREE.MeshStandardMaterial({ color: "#4f9d69", roughness: 0.7, flatShading: true });
  for (let i = 0; i < 5; i++) {
    const leaf = new THREE.Mesh(new THREE.IcosahedronGeometry(0.22 + (i % 2) * 0.06, 0), leafMat);
    leaf.position.set(Math.cos(i * 1.3) * 0.15, 0.45 + (i / 5) * height * 0.6, Math.sin(i * 1.3) * 0.15);
    leaf.castShadow = true;
    g.add(leaf);
  }
  return g;
}

function lamp(x: number, z: number): THREE.Group {
  const g = new THREE.Group();
  g.position.set(x, 0.8, z);
  const metal = new THREE.MeshStandardMaterial({ color: "#3a3f4d", metalness: 0.5, roughness: 0.4 });
  const base = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.12, 0.03, 16), metal);
  const arm = new THREE.Mesh(new THREE.CylinderGeometry(0.015, 0.015, 0.5, 8), metal);
  arm.position.y = 0.25;
  arm.rotation.z = 0.3;
  const shade = new THREE.Mesh(new THREE.ConeGeometry(0.12, 0.15, 16, 1, true), new THREE.MeshStandardMaterial({ color: "#ffd66b", emissive: "#ffb43a", emissiveIntensity: 0.6, side: THREE.DoubleSide }));
  shade.position.set(-0.08, 0.5, 0);
  g.add(base, arm, shade);
  return g;
}

function books(x: number, z: number): THREE.Group {
  const g = new THREE.Group();
  g.position.set(x, 0.8, z);
  ["#8fb4ff", "#c4a7ff", "#ff6b78"].forEach((c, i) => {
    const b = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.28 - i * 0.03, 0.22), new THREE.MeshStandardMaterial({ color: c }));
    b.position.set(i * 0.075, 0.14 - i * 0.015, 0);
    b.castShadow = true;
    g.add(b);
  });
  return g;
}

function woodTexture(dark: boolean): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = c.height = 512;
  const g = c.getContext("2d")!;
  const base = dark ? ["#6b4f3d", "#735643", "#634838"] : ["#d8b98f", "#ceae84", "#e0c39a"];
  for (let i = 0; i < 16; i++) {
    g.fillStyle = base[i % 3];
    g.fillRect(0, i * 32, 512, 32);
    g.fillStyle = dark ? "rgba(0,0,0,0.25)" : "rgba(80,50,20,0.15)";
    g.fillRect(0, i * 32, 512, 1.5);
    g.fillRect(((i * 197) % 512), i * 32, 1.5, 32);
  }
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(4, 4);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function windowTexture(dark: boolean, seed: number): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = 256;
  c.height = 192;
  const g = c.getContext("2d")!;
  const sky = g.createLinearGradient(0, 0, 0, 192);
  sky.addColorStop(0, dark ? "#0b1530" : "#8ec5ff");
  sky.addColorStop(1, dark ? "#24304f" : "#dff0ff");
  g.fillStyle = sky;
  g.fillRect(0, 0, 256, 192);
  for (let i = 0; i < 7; i++) {
    const w = 24 + ((i * 31 + seed * 17) % 30);
    const h = 50 + ((i * 47 + seed * 13) % 90);
    const x = i * 38;
    g.fillStyle = dark ? "#151c30" : "#b9cbe0";
    g.fillRect(x, 192 - h, w, h);
    if (dark) {
      g.fillStyle = "#ffd66b";
      for (let k = 0; k < 6; k++) if ((i + k + seed) % 3 === 0) g.fillRect(x + 5 + (k % 2) * 10, 192 - h + 8 + k * 12, 5, 5);
    }
  }
  g.strokeStyle = "#e8e8e8";
  g.lineWidth = 8;
  g.strokeRect(0, 0, 256, 192);
  g.beginPath();
  g.moveTo(128, 0);
  g.lineTo(128, 192);
  g.stroke();
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function paperTexture(key: string): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = 128;
  c.height = 160;
  const g = c.getContext("2d")!;
  g.fillStyle = "#fffdf5";
  g.fillRect(0, 0, 128, 160);
  g.fillStyle = "#d9d2bf";
  for (let i = 0; i < 8; i++) g.fillRect(12, 56 + i * 12, 104 - (i % 3) * 18, 3);
  g.fillStyle = "#222";
  g.font = "bold 22px monospace";
  g.fillText(key, 10, 36);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function drawBoard(c: HTMLCanvasElement, z: SceneZones, dark: boolean) {
  const g = c.getContext("2d")!;
  g.fillStyle = dark ? "#f3f5f9" : "#ffffff";
  g.fillRect(0, 0, c.width, c.height);
  g.fillStyle = "#1f2a44";
  g.font = "bold 26px sans-serif";
  g.fillText(z.title.slice(0, 30), 20, 40);
  const max = Math.max(1, ...z.board.map((b) => b.n));
  const n = z.board.length;
  const slot = (c.width - 40) / Math.max(1, n);
  z.board.forEach((b, i) => {
    const h = 20 + (b.n / max) * 170;
    const x = 20 + i * slot + slot * 0.2;
    g.fillStyle = ["#ff8a4c", "#8fb4ff", "#c4a7ff", "#34d399", "#ffd66b", "#ff6b78"][i % 6];
    g.fillRect(x, 250 - h, slot * 0.6, h);
    g.fillStyle = "#1f2a44";
    g.font = "bold 18px sans-serif";
    g.fillText(String(b.n), x + slot * 0.18, 244 - h);
    g.font = "13px sans-serif";
    g.fillText(b.name.slice(0, 11), x - 4, 278);
  });
}
