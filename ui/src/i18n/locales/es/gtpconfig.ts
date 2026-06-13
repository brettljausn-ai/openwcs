// Spanish (Español): GTP workplace configuration (admin). Keys mirror useT('gtpconfig', ...) calls.
export default {
  eyebrow: 'Configuración',
  title: 'Puestos GTP',
  intro:
    'Configure los puestos goods-to-person (estaciones): su topología de destino, los modos de operación admitidos y sus nodos STOCK / ORDER. Los puestos están asociados al almacén seleccionado.',
  warehouse: 'Almacén',
  warehouseTip:
    'El almacén cuyos puestos GTP está configurando. Todos los puestos y nodos siguientes pertenecen a este sitio.',
  selectWarehouse: 'Seleccionar un almacén…',
  selectWarehousePrompt: 'Seleccione un almacén arriba para configurar sus puestos GTP.',
  cancel: 'Cancelar',
  save: 'Guardar',
  workplaces: 'Puestos',
  newWorkplace: '+ Nuevo puesto',
  colCode: 'Código',
  colName: 'Nombre',
  colTopology: 'Topología',
  colOperatingModes: 'Modos de operación',
  colNodes: 'Nodos',
  colStatus: 'Estado',
  loading: 'Cargando…',
  noWorkplaces: 'Aún no hay puestos GTP en este almacén.',
  edit: 'Editar',
  delete: 'Eliminar',
  deleteWorkplaceTitle: 'Eliminar puesto',
  deleteWorkplacePrefix: 'Eliminar puesto',
  deleteWorkplaceSuffix: 'y todos sus nodos? Esta acción no se puede deshacer.',
  editWorkplaceTitle: 'Editar puesto',
  newWorkplaceTitle: 'Nuevo puesto',
  code: 'Código',
  name: 'Nombre',
  workplaceCodeTip:
    'Identificador corto y único de este puesto dentro del almacén. Se usa en las pantallas del operador y en el dispositivo.',
  workplaceNameTip:
    'Descripción opcional y legible del puesto, mostrada junto al código para ayudar a los operadores a reconocerlo.',
  workplaceNamePlaceholder: 'p. ej. Pasillo 3 Put-wall',
  destinationTopology: 'Topología de destino',
  destinationTopologyTip:
    'Cómo se disponen los destinos de pedido: ORDER_LOCATION = un destino fijo/de transportador por pedido; PUT_WALL = varios cubículos entre los que reparte el operador. Solo relevante para PICKING.',
  status: 'Estado',
  workplaceStatusTip:
    'Estado del ciclo de vida del puesto. Solo los puestos ACTIVE aceptan trabajo; ARCHIVED lo oculta del uso operativo.',
  supportedOperatingModes: 'Modos de operación admitidos',
  supportedOperatingModesTip:
    'Qué tipos de tarea puede realizar aquí el operador cuando se presenta una HU. PICKING siempre está habilitado; marque los demás para permitirlos.',
  nodesFor: 'Nodos:',
  operatingModes: 'Modos de operación',
  operatingModesHint:
    'Lo que el operador puede hacer en este puesto cuando se presenta una HU. PICKING siempre está disponible.',
  capacity: 'Capacidad',
  newNode: '+ Nuevo nodo',
  nodesHint:
    'Los nodos STOCK presentan una HU de stock al operador; los nodos ORDER son destinos de pedido (una ubicación fija/de transportador en modo ORDER_LOCATION, o un cubículo de put-wall en modo PUT_WALL) y llevan un put-light opcional.',
  colPos: 'Pos',
  colRole: 'Rol',
  colPutLightId: 'Id put-light',
  colLocationId: 'Id de ubicación',
  colOrderHuId: 'Id HU de pedido',
  noNodes: 'No hay nodos configurados. Añada nodos STOCK y ORDER.',
  remove: 'Quitar',
  removeNodeTitle: 'Quitar nodo',
  removeNodePrefix: 'Quitar',
  removeNodeSuffix: 'nodo',
  editNodeTitle: 'Editar nodo',
  newNodeTitle: 'Nuevo nodo',
  role: 'Rol',
  roleTip:
    'Un nodo STOCK presenta al operador una HU de stock de origen; un nodo ORDER es un destino de pedido (ubicación fija o cubículo de put-wall).',
  nodeCodeTip:
    'Identificador corto y único de este nodo dentro del puesto. Se muestra al operador y se usa para direccionar la posición.',
  putLightId: 'Id put-light',
  putLightIdTip:
    'Identificador del dispositivo físico pick/put-to-light o de visualización en este destino, usado para guiar al operador. Déjelo en blanco si no hay ninguno.',
  putLightIdPlaceholder: 'Id de la luz/pantalla física',
  orderHuId: 'Id HU de pedido',
  orderHuIdTip:
    'UUID de la unidad de manipulación de pedido (caja/contenedor) actualmente vinculada a este destino. Normalmente lo establece el sistema; déjelo en blanco si no hay ninguno.',
  orderHuIdPlaceholder: 'UUID (HU de pedido vinculada actualmente)',
  location: 'Ubicación',
  locationTip:
    'La ubicación de datos maestros a la que corresponde este nodo, cuando es una posición fija/de transportador, buscada por código de ubicación. Déjelo en blanco para cubículos dinámicos de put-wall.',
  searchLocationCode: 'Buscar un código de ubicación…',
  position: 'Posición',
  positionTip:
    'Índice de orden que determina dónde aparece este nodo en la disposición del puesto y en la lista de nodos (los números menores primero).',
  nodeStatusTip:
    'Si este nodo está en uso operativo. Los nodos INACTIVE permanecen en el puesto pero se omiten durante el trabajo.',
  inTransitCapacity: 'Capacidad en tránsito',
  capacityHint:
    'Cuántas unidades de manipulación (contenedores) pueden tener un transporte en camino a este puesto a la vez, limitado por separado según la clase de modo. Picking es el caso de alto rendimiento; Otros cubre decantar, contar, QC y mantenimiento.',
  maxInTransitPicking: 'HUs máx. en tránsito: Picking',
  maxInTransitPickingTip:
    'Limita cuántas HUs pueden tener un transporte PICKING activo entrante a este puesto a la vez. Más alto mantiene abastecido al operador; demasiado alto satura el búfer de entrada.',
  maxInTransitOther: 'HUs máx. en tránsito: Otros (sin picking)',
  maxInTransitOtherTip:
    'Limita cuántas HUs pueden tener un transporte sin picking activo (decantar, contar, QC, mantenimiento) entrante a este puesto a la vez.',
  noLocations: 'No hay ubicaciones en este almacén.',
  noMatchingCode: 'Ningún código coincidente.',
}
