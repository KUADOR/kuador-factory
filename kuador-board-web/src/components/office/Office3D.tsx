"use client";

import { useEffect, useRef, useState } from "react";

import type { OfficeScene, SceneSeat, SceneZones } from "./scene3d";

/**
 * Monta la oficina 3D (three.js se carga solo en esta página). Si el navegador no tiene WebGL muestra {@code fallback}
 * (la vista en lista). La escena vive mientras el componente esté montado; los datos nuevos se le pasan sin rearmarla.
 */
export function Office3D({
  seats,
  zones,
  onSelect,
  sceneRef,
  autoRotate,
  paused,
  fallback,
}: {
  seats: SceneSeat[];
  zones: SceneZones;
  onSelect: (id: string) => void;
  /** Para que la oficina le pida a la escena que vuele un papel. */
  sceneRef: React.RefObject<OfficeScene | null>;
  autoRotate: boolean;
  paused: boolean;
  fallback: React.ReactNode;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);
  const latest = useRef({ seats, zones, onSelect, autoRotate });

  useEffect(() => {
    latest.current = { seats, zones, onSelect, autoRotate };
  });

  useEffect(() => {
    let scene: OfficeScene | null = null;
    let cancelled = false;
    void import("./scene3d").then(({ OfficeScene }) => {
      if (cancelled || !host.current) return;
      try {
        scene = new OfficeScene(host.current, {
          dark: document.documentElement.classList.contains("app-dark"),
          reducedMotion: matchMedia("(prefers-reduced-motion: reduce)").matches,
          onSelect: (id) => latest.current.onSelect(id),
        });
        scene.setData(latest.current.seats, latest.current.zones);
        scene.setAutoRotate(latest.current.autoRotate);
        sceneRef.current = scene;
      } catch {
        setFailed(true);
      }
    });
    return () => {
      cancelled = true;
      scene?.dispose();
      sceneRef.current = null;
    };
  }, [sceneRef]);

  const dataKey = JSON.stringify([seats, zones]);
  useEffect(() => {
    sceneRef.current?.setData(latest.current.seats, latest.current.zones);
  }, [dataKey, sceneRef]);

  useEffect(() => {
    sceneRef.current?.setAutoRotate(autoRotate);
  }, [autoRotate, sceneRef]);

  useEffect(() => {
    sceneRef.current?.setPaused(paused);
  }, [paused, sceneRef]);

  if (failed) return <>{fallback}</>;
  return <div ref={host} className="office3d relative h-full w-full overflow-hidden" aria-hidden />;
}
