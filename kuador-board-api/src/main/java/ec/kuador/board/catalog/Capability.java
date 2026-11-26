package ec.kuador.board.catalog;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lo que un tablero sabe hacer además del flujo de tarjetas. Cada plantilla enciende lo que usa; así una clínica puede
 * tener documentos con versiones sin tener repositorio de código.
 */
public enum Capability {
    /** Documentos con versiones (los de cada tarjeta). */
    DOCUMENTS,
    /**
     * Usa lo de sus proyectos: el manual de trabajo y los documentos base (los comparten los tableros que trabajan el
     * proyecto). Un tablero de marketing trabaja el proyecto sin ellos; uno de código, una clínica con su manual, con ellos.
     */
    PROJECT_DOCS,
    /** Repositorio de código: piezas, ramas, Pull Requests, CI, sincronización y kit de agentes. */
    REPOSITORY,
    /** Repartir una tarjeta grande en tareas con responsable y dependencias. */
    SPLIT;

    /** Todo: lo que traía un tablero de software. */
    public static final List<Capability> ALL = List.of(values());

    /**
     * Lo que vale para lo guardado antes de las capacidades: software lo tenía todo; los demás tableros ganan los
     * documentos (no hacen nada hasta elegir un manual). El reparto se enciende a propósito: en ventas o marketing una
     * «campaña» agrupa tarjetas y no debe convertirse sola en un plan con tareas.
     */
    public static List<Capability> defaultsFor(ProjectKind kind) {
        return kind == ProjectKind.SOFTWARE ? ALL : List.of(DOCUMENTS);
    }

    /** Sin repetidos, en orden fijo; el repositorio guarda los documentos del proyecto, así que los incluye. */
    public static List<Capability> normalize(List<Capability> capabilities) {
        Set<Capability> set = EnumSet.noneOf(Capability.class);
        for (Capability c : capabilities) if (c != null) set.add(c);
        if (set.contains(REPOSITORY)) set.add(PROJECT_DOCS);
        if (set.contains(PROJECT_DOCS)) set.add(DOCUMENTS);
        return List.copyOf(set);
    }

    /** El tipo que ven los clientes de la API anteriores a las capacidades. */
    public static ProjectKind kindOf(List<Capability> capabilities) {
        return capabilities.contains(REPOSITORY) ? ProjectKind.SOFTWARE : ProjectKind.GENERAL;
    }
}
