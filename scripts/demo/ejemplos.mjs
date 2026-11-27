// El contenido de las tarjetas de demostración: qué pidió el negocio (descripción) y el rastro del trabajo hecho
// (comentarios de cada agente y de la persona), para que cada tarjeta se lea como un caso real.
// Clave: tablero → título de la tarjeta. «by»: el rol del agente que comenta, o PERSONA para la cuenta de demo.

const P = "PERSONA";

export const examples = {
  OFI: {
    "Minuta de la reunión de directorio del martes": {
      description: `**Pedido de Gerencia**

Necesito la minuta formal de la reunión de directorio del martes 25 para enviarla a los socios antes del viernes.

Notas sueltas:
- Presupuesto 2027: se aprueba con un recorte del 8% en viajes.
- Nueva sucursal en Cuenca: se busca local hasta marzo; responsable Carla.
- Proveedor de internet: cambiar a Fibra Total por caídas frecuentes (Andrés pide 3 cotizaciones).
- Próxima reunión: martes 16 de diciembre, 9:00.

Asistentes: Diego (presidente), Carla, Andrés, Mónica (secretaria).`,
      trail: [],
    },
    "Responder al arrendador sobre la renovación del contrato": {
      description: `El arrendador (Sr. Molina) escribió que el contrato vence el 31 de enero y propone renovar por 2 años con un alza del 6%.

Queremos renovar, pero máximo con 3% de alza y 1 año, con opción a otro. Redactar una respuesta cordial y firme.`,
      trail: [],
    },
    "Lista de compras de suministros de diciembre": {
      description: "Armar la lista de compras de suministros para diciembre con lo que se gastó en noviembre (inventario adjunto por Mónica) y un 10% extra por las fiestas.",
      trail: [
        { by: "ASISTENTE", text: `Lista propuesta para diciembre (consumo de noviembre + 10%):

| Artículo | Cantidad | Costo aprox. |
|---|---|---|
| Resmas de papel A4 | 22 | USD 99,00 |
| Tóner HP 26A | 3 | USD 255,00 |
| Café molido 500 g | 9 | USD 58,50 |
| Botellones de agua | 18 | USD 45,00 |
| Kit de limpieza | 2 | USD 64,00 |
| **Total** | | **USD 521,50** |

Revisa antes de comprar: el tóner subió de precio en Papelería Central; Suministros Quito lo tiene a USD 79.` },
      ],
    },
    "Carta de felicitación a clientes por fin de año": {
      description: "Carta corta para los 40 clientes principales: agradecer el año y anunciar los horarios de diciembre.",
      trail: [
        { by: "ASISTENTE", text: `Borrador:

> Estimado cliente: gracias por confiar en nosotros durante 2026. Fue un año de crecer juntos y queremos seguir haciéndolo. Les recordamos que el 24 y el 31 de diciembre atenderemos de 9:00 a 13:00. ¡Felices fiestas!

Revisa: el nombre de quien firma y si incluimos el descuento de enero.` },
        { by: P, text: "Aprobado. Firma Diego. Sin descuento, lo anunciamos en enero." },
      ],
    },
    "Agenda de la capacitación de seguridad": {
      description: "Capacitación anual de seguridad y evacuación con el cuerpo de bomberos. 2 horas, todo el personal.",
      trail: [
        { by: "ASISTENTE", text: "Agenda: 9:00 bienvenida · 9:15 rutas de evacuación · 10:00 uso de extintores (práctica en el parqueadero) · 10:45 preguntas · 11:00 cierre. Convocatoria redactada para enviar el lunes." },
        { by: P, text: "Listo, enviada. Se hizo el 20 de noviembre con 23 asistentes." },
      ],
    },
  },

  REC: {
    "Consulta: ¿hacen envíos a Cuenca?": {
      description: `**De:** maria.paz@gmail.com
**Asunto:** Envíos

Hola, quería saber si hacen envíos a Cuenca y cuánto demora. Me interesa el pedido de 12 cajas. Gracias, María.`,
      trail: [],
    },
    "Reclamo: el pedido 4512 llegó incompleto": {
      description: `**De:** compras@ferreteriaandes.ec
**Asunto:** URGENTE pedido 4512 incompleto

Buenos días. Recibimos hoy el pedido 4512 y faltan 3 de las 20 cajas de tornillos 1/4. Necesitamos el faltante esta semana porque tenemos una obra. Adjunto la guía de remisión firmada.

Roberto Paredes — Jefe de compras, Ferretería Andes`,
      trail: [],
    },
    "Factura 001-002-000318 de Papelería Central": {
      description: `**De:** facturacion@papeleriacentral.ec
**Asunto:** Factura electrónica 001-002-000318

Adjuntamos la factura electrónica por USD 312,40 correspondiente a la orden OC-1201.`,
      trail: [
        { by: "RECEPCIONISTA", text: "Clasificado como **Factura** de proveedor. No respondo: lo paso a Contabilidad (tablero Cuentas por pagar) con el PDF y la orden OC-1201 que menciona." },
      ],
    },
    "Consulta: horarios de atención en feriado": {
      description: `**De:** jorge.v@hotmail.com
**Asunto:** Horario

¿Van a atender el 24 de diciembre? Necesito retirar un pedido.`,
      trail: [
        { by: "RECEPCIONISTA", text: `Clasificado como **Consulta**. Respuesta propuesta:

> Hola Jorge, gracias por escribirnos. El 24 y el 31 de diciembre atendemos de 9:00 a 13:00, así que puedes retirar tu pedido en ese horario. Si prefieres, también te lo enviamos sin costo dentro de Quito. ¡Saludos!` },
      ],
    },
    "Gana un iPhone haciendo clic aquí": {
      description: "**De:** promo@premios-ya.biz\n**Asunto:** ¡¡Ganaste!! Reclama tu premio hoy",
      trail: [{ by: "RECEPCIONISTA", text: "Clasificado como **Spam**: remitente desconocido, enlace sospechoso y promesa de premio. Descartado sin abrir el enlace." }],
    },
  },

  CIT: {
    "Cita — Lucía Andrade": {
      description: "Solicitud por WhatsApp: «Hola, quisiera una cita para mi primera consulta, de preferencia en la mañana esta semana o la próxima.»",
      trail: [],
    },
    "Cita — Pedro Salazar": {
      description: "Control mensual de presión arterial. Pidió lunes en la mañana.",
      trail: [
        { by: "AGENDADOR", text: `Mensaje de confirmación:

> Hola Pedro, tu cita de control quedó para el **lunes 1 de diciembre a las 10:30** en Av. Amazonas N34-120, consultorio 3. Trae tu registro de presión de este mes. Si no puedes asistir, avísanos respondiendo este mensaje.` },
        { by: P, text: "Confirmó por WhatsApp." },
      ],
    },
    "Cita — Ana Morales": {
      description: "Revisión de resultados de laboratorio (perfil lipídico).",
      trail: [{ by: "AGENDADOR", text: "Confirmada para el lunes 1 de diciembre a las 11:15. Le pedí traer los resultados impresos o enviarlos por correo antes de la cita." }],
    },
    "Cita — Carlos Ríos": {
      description: "Control post operatorio.",
      trail: [
        { by: "AGENDADOR", text: "Recordatorio enviado el 24: «Carlos, te esperamos mañana a las 9:00. Llega 10 minutos antes.»" },
        { by: P, text: "Atendido. Próximo control en 3 meses." },
      ],
    },
    "Cita — Daniela Vera": {
      description: "Primera consulta, referida por el Dr. Ortiz.",
      trail: [
        { by: "AGENDADOR", text: "Recordatorio enviado el 23 a las 17:00. Sin respuesta." },
        { by: P, text: "No asistió. La llamé y pide reagendar para enero." },
      ],
    },
  },

  VEN: {
    "Florícola Rosas del Valle": {
      description: "La conocimos en la feria Agroflor. Exporta rosas a Estados Unidos y Rusia; su gerente de operaciones dijo que pierden mucho tiempo registrando facturas de proveedores a mano.",
      trail: [],
    },
    "Constructora Horizonte": {
      description: "Nos escribió su director administrativo por LinkedIn preguntando por automatizar la aprobación de compras de obra.",
      trail: [
        { by: "INVESTIGADOR", text: `**Ficha del prospecto** (en curso)

- **Quiénes son:** constructora mediana de Quito, 12 proyectos de vivienda activos (sitio web).
- **Tamaño:** ~180 empleados según LinkedIn.
- **Noticia reciente:** ganaron la licitación del conjunto «Los Cedros» (oct. 2026).
- **Qué podrían necesitar:** aprobar compras de obra desde el celular y cruzar facturas con órdenes.

Me falta confirmar quién decide la compra. Lo pregunto en el primer contacto.` },
      ],
    },
    "Clínica San Rafael": {
      description: "Referida por un cliente. Quieren automatizar la agenda de citas y los recordatorios (hoy pierden un 18% por inasistencias).",
      trail: [
        { by: "INVESTIGADOR", text: "Ficha: clínica privada con 14 especialistas, 2 sedes. Decide la gerente general (Dra. Paredes). Puntaje **5**: dolor claro y medible, presupuesto aprobado para 2027." },
        { by: P, text: "Primera reunión muy buena. Quieren arrancar en enero con agenda + recordatorios + encuesta. Monto acordado USD 4.800 la implementación." },
        { by: "PROPUESTAS", text: `**Propuesta lista para revisar**

1. **El problema:** 18% de inasistencias y la recepción saturada confirmando citas por teléfono.
2. **La solución:** tablero de agenda con agente que confirma, recuerda el día anterior y reagenda; encuesta al terminar.
3. **Alcance:** 2 sedes, 14 agendas, conexión con WhatsApp Business.
4. **Plazos:** 4 semanas desde la firma.
5. **Precio:** USD 4.800 de implementación + USD 79/mes.

Revisa: no incluí descuento por pago anticipado porque no se conversó.` },
      ],
    },
    "Importadora El Puerto": {
      description: "Importadora de repuestos en Guayaquil. Interesados en cobranzas y cuentas por pagar.",
      trail: [
        { by: "INVESTIGADOR", text: "Ficha: 60 empleados, cartera vencida alta según su propio gerente (25%). Puntaje 4." },
        { by: "PROPUESTAS", text: "Propuesta enviada el 18/11: cobranzas + cuentas por pagar, USD 12.500 implementación." },
        { by: P, text: "Piden 10% de descuento y pagar en 3 cuotas. Les ofrecí 5% o las 3 cuotas, no ambas. Esperamos respuesta el lunes." },
      ],
    },
    "Colegio Los Pinos": {
      description: "Automatizar la recepción de correos de padres de familia y la agenda de reuniones.",
      trail: [
        { by: "PROPUESTAS", text: "Propuesta: recepción de correos + agenda de reuniones. USD 3.200." },
        { by: P, text: "¡Firmado! Arrancamos el 2 de diciembre." },
      ],
    },
    "Taller Mecánico Ruiz": {
      description: "Llamada en frío.",
      trail: [{ by: "INVESTIGADOR", text: "Puntaje 2: taller de 4 personas, sin correo corporativo ni sistema. Hoy no es un cliente para nosotros; propongo retomarlo en un año." }],
    },
  },

  MKT: {
    "Campaña de Navidad: 5 piezas": {
      description: "Campaña de diciembre: 5 piezas entre el 1 y el 20 de diciembre para Instagram, LinkedIn y correo. Mensaje: «regala tiempo a tu equipo: automatiza tu oficina».",
      trail: [],
    },
    "Post: 3 errores al elegir proveedor": {
      description: "Idea: post educativo sobre 3 errores comunes al elegir proveedores (solo precio, sin contrato, sin plan B).",
      trail: [],
    },
    "Carrusel: cómo funciona nuestro servicio": {
      description: "Carrusel de 5 láminas que explique en simple cómo trabaja un tablero con agentes.",
      trail: [
        { by: "REDACTOR", text: `**Brief:** público dueños de pymes · objetivo agendar demos · éxito = 15 mensajes directos.

**Texto por lámina:**
1. «¿Tu oficina vive apagando incendios?»
2. «Cada pedido entra a un tablero.»
3. «Un agente hace el primer borrador.»
4. «Tú apruebas en un clic.»
5. «Agenda tu demo → enlace en la bio.»` },
        { by: "DISENADOR", text: "Diseño: fondo morado de la marca, ilustraciones lineales en blanco, una idea por lámina, tipografía grande. Formato 1080×1350. Imágenes adjuntas para revisar." },
      ],
    },
    "Correo: novedades de noviembre": {
      description: "Boletín mensual para la base de 1.200 contactos.",
      trail: [
        { by: "REDACTOR", text: "Asunto: «3 cosas nuevas que tu equipo ya puede delegar». Texto listo con los 3 casos de clientes y un llamado a la demo." },
        { by: P, text: "Aprobado. Programado para el domingo 30 a las 19:00." },
      ],
    },
    "Video corto: un día en la oficina": {
      description: "Video de 30 s mostrando un día en la oficina con el tablero.",
      trail: [
        { by: P, text: "Publicado el 18/11. Adjunto las cifras: 4.300 reproducciones, 210 me gusta, 12 compartidos, 6 mensajes." },
        { by: "MEDIDOR", text: `**Resultado:** el brief pedía 3.000 reproducciones y 5 mensajes → **cumplido**.

- Funcionó: el gancho de los primeros 3 segundos (retención 62%).
- No funcionó: el texto final era muy largo; casi nadie llegó al llamado a la acción.
- Repetir: formato de oficina real; acortar el cierre a una frase.` },
      ],
    },
  },

  CXP: {
    "Factura Transportes Sierra — noviembre": {
      description: "Llegó por correo a facturacion@ el 26/11 con el PDF adjunto. Flete de exportación del mes.",
      trail: [],
    },
    "Factura 001-001-004521 Agroquímicos del Sur": {
      description: "Factura de fertilizantes para la finca, orden de compra OC-1187.",
      trail: [
        { by: "LECTOR_FACTURAS", text: "Datos leídos: Agroquímicos del Sur · RUC 1791234567001 · Nº 001-001-004521 · 45 sacos × USD 52,01 · subtotal USD 2.340,50 · IVA 0% · vence 2026-12-15 · orden OC-1187." },
        { by: "CONCILIADOR", text: `**Hay diferencias** con la orden OC-1187:

| | Orden | Factura |
|---|---|---|
| Cantidad | 40 sacos | **45 sacos** |
| Precio unitario | USD 52,01 | USD 52,01 |
| Total | USD 2.080,40 | **USD 2.340,50** |

La bodega registró la recepción de 40 sacos. Pasa a revisión manual: pedir nota de crédito por 5 sacos (USD 260,10) o confirmar si llegaron después.` },
      ],
    },
    "Factura 002-001-000877 Cartonera Andina": {
      description: "Cajas de exportación para la temporada de San Valentín.",
      trail: [
        { by: "LECTOR_FACTURAS", text: "Cartonera Andina · RUC 1790012345001 · Nº 002-001-000877 · 8.000 cajas × USD 0,64 · total USD 5.120,00 · vence 2026-12-10 · OC-1192." },
        { by: "CONCILIADOR", text: "**Todo cuadra** con la OC-1192: cantidad, precio y total coinciden; recepción completa en bodega el 24/11; no hay otra factura con ese número. Listo para aprobar el pago." },
      ],
    },
    "Factura 001-003-012003 Internet Fibra": {
      description: "Servicio de internet de diciembre.",
      trail: [
        { by: "CONCILIADOR", text: "Servicio recurrente: coincide con el contrato (USD 80 + IVA). Todo cuadra." },
        { by: P, text: "Aprobado. Pago programado para el 5/12." },
      ],
    },
    "Factura 001-001-000312 Seguridad Alfa": {
      description: "Guardianía de noviembre.",
      trail: [
        { by: "CONCILIADOR", text: "Coincide con el contrato mensual. Todo cuadra." },
        { by: P, text: "Pagada por transferencia el 25/11. Comprobante adjunto." },
      ],
    },
  },

  COB: {
    "Comercial Montalvo — F-2231": {
      description: "Factura F-2231 por USD 780 venció el 24/11. Cliente con buen historial (siempre pagó, a veces con unos días de atraso).",
      trail: [],
    },
    "Hotel Mirador — F-2198": {
      description: "Factura F-2198 por USD 2.150, vencida hace 21 días.",
      trail: [
        { by: "COBRADOR", text: `Recordatorio 1 (enviado el 11/11):

> Estimados, les recordamos que la factura F-2198 por USD 2.150 venció el 6 de noviembre. Pueden pagar por transferencia a la cuenta 220045… Si ya pagaron, por favor ignoren este mensaje. ¡Gracias!` },
        { by: "COBRADOR", text: `Recordatorio 2 (borrador):

> Estimados, la factura F-2198 por USD 2.150 tiene 21 días de atraso. Para mantener su línea de crédito activa necesitamos el pago o un compromiso de fecha hasta el viernes 5 de diciembre. Quedamos atentos.` },
      ],
    },
    "Distribuidora Luz — F-2150": {
      description: "Factura F-2150 por USD 4.300, 45 días de atraso. Ya recibió los dos recordatorios sin responder.",
      trail: [
        { by: "COBRADOR", text: `**Guion para la llamada**

- Saldo: USD 4.300 (F-2150), 45 días.
- Historia: pagaba a 30 días hasta agosto; desde septiembre se atrasa.
- Abrir: «Llamamos para ayudarte a ponerte al día con la F-2150».
- Opción A: pago total hasta el 10/12.
- Opción B: 2 cuotas de USD 2.150 (10/12 y 10/01).
- Si no hay acuerdo: avisar que se suspende el crédito el 15/12.` },
      ],
    },
    "Panadería Don Pepe — F-2177": {
      description: "Factura F-2177 por USD 320.",
      trail: [
        { by: "COBRADOR", text: "Dos recordatorios enviados; sin respuesta." },
        { by: P, text: "Llamé: el dueño se compromete a pagar el 5 de diciembre. Anoté la promesa." },
      ],
    },
    "Agencia Viajes Sol — F-2102": {
      description: "Factura F-2102 por USD 6.000, 60 días.",
      trail: [
        { by: P, text: "Acuerdo firmado: 3 cuotas de USD 2.000 los días 15 de diciembre, enero y febrero. Documento adjunto." },
      ],
    },
  },

  SOP: {
    "No puedo descargar mi factura de octubre": {
      description: "**Marta Cedeño:** «Entro al portal, hago clic en Descargar y no pasa nada. Necesito la factura para mi declaración.»",
      trail: [],
    },
    "El producto llegó con la caja dañada": {
      description: "**Tienda La Esquina:** llegaron 2 de 10 cajas aplastadas, con producto dañado. Adjuntan fotos.",
      trail: [
        { by: "TRIAGE", text: `Gravedad **Alta** · Categoría **Entrega**.

Resumen para quien resuelve: 2 cajas dañadas del pedido 4498 (fotos adjuntas, se ve el daño de transporte). El cliente pide reposición, no devolución de dinero. La transportista fue Rápido Express: corresponde abrir reclamo con ellos.` },
      ],
    },
    "Cambio de correo para recibir avisos": {
      description: "**Juan Pérez:** quiere recibir los avisos en su nuevo correo.",
      trail: [
        { by: "TRIAGE", text: "Gravedad Baja · Acceso. Le pedí confirmar el nuevo correo respondiendo desde la cuenta anterior (por seguridad)." },
      ],
    },
    "Cobro duplicado en la tarjeta": {
      description: "**Sofía León:** «Me cobraron dos veces USD 129 el 20 de noviembre.» Adjunta el estado de la tarjeta.",
      trail: [
        { by: "TRIAGE", text: "Gravedad **Crítica** · Facturación. Confirmado en la pasarela: dos cobros con 40 segundos de diferencia (doble clic). Corresponde reversar uno." },
        { by: P, text: "Reverso hecho en la pasarela (ID 88123). Le escribí a la clienta; falta que confirme que lo ve en su estado." },
      ],
    },
    "Agregar seguimiento del pedido por WhatsApp": {
      description: "Idea que sale de varios casos: que el cliente reciba el estado de su pedido por WhatsApp sin tener que escribirnos.",
      trail: [],
    },
    "Clave bloqueada tras 3 intentos": {
      description: "**Luis Castro:** no puede entrar al portal.",
      trail: [
        { by: "TRIAGE", text: "Respuesta frecuente: le envié el enlace para restablecer la clave y los pasos." },
        { by: "ENCUESTADOR", text: "Encuesta enviada: «¿Quedó resuelto?» → Sí · Puntaje 5 · «Rapidísimo, gracias»." },
      ],
    },
  },

  RRHH: {
    "Asistente contable": {
      description: `**Perfil:** CPA o estudiante de últimos niveles, 2 años de experiencia en registro de compras y conciliaciones, manejo de Excel intermedio.
**Salario:** USD 650–750. **Ingreso:** enero.`,
      trail: [
        { by: "RECLUTADOR", text: "Aviso publicado en Multitrabajos y LinkedIn el 15/11. Llegaron 38 hojas de vida (adjuntas)." },
        { by: "RECLUTADOR", text: `**Filtro de hojas de vida** (8 de 38 cumplen todo)

| Nombre | Experiencia | Cumple | Recomendación |
|---|---|---|---|
| Andrea Villacís | 3 años | Todo | Entrevistar |
| Kevin Lara | 2 años | Todo | Entrevistar |
| Paola Ruiz | 4 años | Falta Excel | Entrevistar (prueba de Excel) |
| … | | | |

Descarté 30 por no tener experiencia en conciliaciones.` },
      ],
    },
    "Ejecutivo de ventas Guayaquil": {
      description: "Venta consultiva B2B en Guayaquil, con auto propio. Salario fijo + comisiones.",
      trail: [
        { by: "RECLUTADOR", text: "3 finalistas para entrevista: Mateo S., Gabriela T., Luis P. Hojas de vida y notas adjuntas." },
        { by: P, text: "Entrevistas el 2 y 3 de diciembre con el gerente comercial." },
      ],
    },
    "Coordinadora de operaciones": {
      description: "Oferta aceptada por Valeria Gómez. Ingresa el 8 de diciembre.",
      trail: [
        { by: P, text: "Oferta aprobada: USD 1.400, ingreso 8/12." },
        { by: "ONBOARDING", text: "Repartí el ingreso en 5 tareas con responsable (abajo). La más urgente: la computadora, que tarda 5 días en llegar." },
      ],
    },
    Recepcionista: {
      description: "Vacante cubierta en noviembre.",
      trail: [{ by: P, text: "Ingresó Camila Ortiz el 3 de noviembre. Vacante cerrada." }],
    },
  },

  CIERRE: {
    "Cierre de noviembre 2026": {
      description: `Cierre contable de noviembre. Archivos adjuntos: estados de cuenta de Pichincha y Produbanco, mayor general, rol de pagos, ventas y compras del mes.

**Día límite:** 7 de diciembre.`,
      trail: [
        { by: "REPORTES", text: "Repartí el cierre en 5 tareas (abajo): 2 conciliaciones bancarias, nómina, impuestos y reportes. Cada una con su agente." },
      ],
    },
    "Conciliar Banco Pichincha cuenta corriente": {
      description: "Cuenta corriente 2100…4512.",
      trail: [
        { by: "CONCILIADOR_BANCOS", text: `**Conciliación Pichincha — noviembre**

- Saldo banco: USD 48.215,30 · Saldo libros: USD 47.905,30
- Diferencia: **USD 310,00**, explicada así:
  - Cheque 1123 a Seguridad Alfa (USD 1.450) cobrado, registrado en libros ✓
  - Depósito de Hotel Mirador del 29/11 (USD 310) **no registrado en libros** → falta registrar.
- Comisiones bancarias USD 12,40: falta el asiento.

Saldo conciliado tras los 2 asientos: USD 48.215,30.` },
      ],
    },
    "Revisar nómina de noviembre": {
      description: "Rol de pagos de noviembre (24 personas).",
      trail: [
        { by: "NOMINA", text: `Variaciones contra octubre:

- Horas extra de Bodega: +38% (temporada). Están aprobadas por el jefe de bodega ✓.
- **Marco T.:** el descuento del préstamo quirografario no aparece este mes. Revisar.
- Provisiones de décimo tercero y cuarto: bien calculadas.` },
      ],
    },
    "Borrador IVA y retenciones de noviembre": {
      description: "Formularios 104 y 103.",
      trail: [{ by: "TRIBUTARIO", text: "Borrador listo: IVA en ventas USD 6.820,40 · crédito tributario USD 4.112,15 · a pagar USD 2.708,25. Retenciones en la fuente USD 1.204,60. Vence el 18/12 (noveno dígito 7). Revisar con el contador antes de presentar." }],
    },
    "Estados financieros y resumen ejecutivo": {
      description: "Estado de resultados y balance de noviembre con el resumen para la gerencia.",
      trail: [],
    },
    "Conciliar Produbanco ahorros": {
      description: "Cuenta de ahorros 0570…221.",
      trail: [{ by: "CONCILIADOR_BANCOS", text: "Conciliada sin diferencias: saldo USD 12.400,00 en banco y en libros. Solo intereses del mes (USD 18,30), ya registrados." }],
    },
    "Cierre de octubre 2026": {
      description: "Cierre de octubre.",
      trail: [
        { by: "REPORTES", text: "Resumen ejecutivo de octubre: ventas +12% contra septiembre, margen 31%, gastos de viaje 18% sobre presupuesto." },
        { by: P, text: "Firmado por el contador y la gerencia el 6/11. Declaraciones presentadas." },
      ],
    },
  },
};
