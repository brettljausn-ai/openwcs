// Spanish (Español) - slotting screen (pick faces + block slotting).
export default {
  pickFacesHeading: 'Posiciones de picking (ubicación manual · mín/máx)',
  pickFacesIntro:
    'Asigne un artículo + unidad de medida a una ubicación de picking fija. El reabastecimiento la rellena hasta el máximo; las entradas pueden ir directamente aquí cuando el picking directo está activado.',
  colLocation: 'Ubicación',
  tipLocation:
    'La ubicación de picking fija (frente de estantería/contenedor) a la que está asignado este artículo. Los preparadores siempre acuden aquí para este artículo.',
  colSku: 'Artículo',
  tipSku: 'El artículo de stock asignado a este frente de picking.',
  colUom: 'UdM',
  tipUom: 'Unidad de medida con la que se prepara desde este frente; la cantidad preparada se cuenta en estas unidades.',
  colMin: 'Mín',
  tipMin: 'Disparador de reabastecimiento: cuando las existencias en el frente bajan a este valor o por debajo, se genera una tarea de reposición.',
  colMax: 'Máx',
  tipMax: 'Nivel de llenado objetivo. El reabastecimiento repone el frente hasta esta cantidad.',
  colDirect: 'Directo',
  tipDirect:
    'Cuando está activado, el stock entrante de este artículo puede ubicarse directamente en el frente de picking en lugar de en el almacenamiento de reserva.',
  phLocation: 'ubicación',
  phSku: 'artículo',
  phUom: 'udm',
  phMin: 'mín',
  phMax: 'máx',
  directToPick: 'picking directo',
  addPickFace: 'Añadir frente de picking',
  blockSlottingHeading: 'Ubicación por bloque (ASRS / AutoStore / AMR-GTP automatizados)',
  blockSlottingIntro:
    'Asigne un artículo a un bloque de almacenamiento (todo el pool, todos los pasillos). El motor de ubicación elige la posición real por unidad de manipulación, equilibrando velocidad hacia la salida, consolidación del mismo artículo, redundancia de pasillos y equilibrio de llenado.',
  tipBlockSku:
    'El artículo de stock que se asigna a un bloque de almacenamiento automatizado (pool ASRS / AutoStore / AMR-GTP).',
  colBlock: 'Bloque',
  tipBlock:
    'El bloque de almacenamiento (todo el pool, todos los pasillos) donde puede almacenarse este artículo. El motor de ubicación elige la posición exacta por unidad de manipulación.',
  colVelocity: 'Velocidad',
  tipVelocity:
    'Clase de rotación que determina lo cerca de la salida/picking que se almacena el artículo. A = de rotación rápida, C = de rotación lenta.',
  colConsolidate: 'Consolidar',
  tipConsolidate:
    'Cuando está activado, el motor prefiere agrupar el mismo artículo (menos posiciones y más densas) en lugar de dispersarlo.',
  colMinAisles: 'Pasillos mín.',
  tipMinAisles:
    'Número mínimo de pasillos distintos por los que debe repartirse este artículo, para redundancia si un pasillo queda fuera de servicio.',
  colMaxAislePct: '% pasillo máx.',
  tipMaxAislePct:
    'Tope de la fracción de un pasillo que un solo artículo puede ocupar, para mantener los pasillos equilibrados (0 a 1).',
  phBlock: 'bloque…',
  velocityClass: 'Clase de velocidad',
  consolidate: 'consolidar',
  phMinAisles: 'pasillos mín.',
  phMaxAislePct: '% pasillo máx.',
  addBlockSlotting: 'Añadir ubicación por bloque',
}
