// Brazilian Portuguese (Português do Brasil) - GTP workplace configuration (admin). As chaves espelham as chamadas useT('gtpconfig', ...).
export default {
  eyebrow: 'Configuração',
  title: 'Postos GTP',
  intro:
    'Configure os postos goods-to-person (estações): a topologia de destino, os modos de operação suportados e seus nós STOCK / ORDER. Os postos estão associados ao armazém selecionado.',
  warehouse: 'Armazém',
  warehouseTip:
    'O armazém cujos postos GTP você está configurando. Todos os postos e nós abaixo pertencem a este site.',
  selectWarehouse: 'Selecionar um armazém…',
  selectWarehousePrompt: 'Selecione um armazém acima para configurar seus postos GTP.',
  cancel: 'Cancelar',
  save: 'Salvar',
  workplaces: 'Postos',
  newWorkplace: '+ Novo posto',
  colCode: 'Código',
  colName: 'Nome',
  colTopology: 'Topologia',
  colOperatingModes: 'Modos de operação',
  colNodes: 'Nós',
  colStatus: 'Status',
  loading: 'Carregando…',
  noWorkplaces: 'Ainda não há postos GTP neste armazém.',
  edit: 'Editar',
  delete: 'Excluir',
  deleteWorkplaceTitle: 'Excluir posto',
  deleteWorkplacePrefix: 'Excluir posto',
  deleteWorkplaceSuffix: 'e todos os seus nós? Esta ação não pode ser desfeita.',
  editWorkplaceTitle: 'Editar posto',
  newWorkplaceTitle: 'Novo posto',
  code: 'Código',
  name: 'Nome',
  workplaceCodeTip:
    'Identificador curto e único deste posto dentro do armazém. Usado nas telas do operador e no dispositivo.',
  workplaceNameTip:
    'Descrição opcional e legível do posto, exibida junto ao código para ajudar os operadores a reconhecê-lo.',
  workplaceNamePlaceholder: 'ex.: Corredor 3 Put-wall',
  destinationTopology: 'Topologia de destino',
  destinationTopologyTip:
    'Como os destinos do pedido são organizados: ORDER_LOCATION = um destino fixo/de transportador por pedido; PUT_WALL = vários compartimentos entre os quais o operador distribui. Relevante apenas para PICKING.',
  status: 'Status',
  workplaceStatusTip:
    'Status do ciclo de vida do posto. Somente os postos ACTIVE aceitam trabalho; ARCHIVED o oculta do uso operacional.',
  supportedOperatingModes: 'Modos de operação suportados',
  supportedOperatingModesTip:
    'Que tipos de tarefa o operador pode realizar aqui quando uma HU é apresentada. PICKING está sempre habilitado; marque os demais para permiti-los.',
  nodesFor: 'Nós:',
  operatingModes: 'Modos de operação',
  operatingModesHint:
    'O que o operador pode fazer neste posto quando uma HU é apresentada. PICKING está sempre disponível.',
  capacity: 'Capacidade',
  newNode: '+ Novo nó',
  nodesHint:
    'Os nós STOCK apresentam uma HU de estoque ao operador; os nós ORDER são destinos de pedido (uma localização fixa/de transportador no modo ORDER_LOCATION, ou um compartimento de put-wall no modo PUT_WALL) e possuem um put-light opcional.',
  colPos: 'Pos',
  colRole: 'Função',
  colPutLightId: 'Id do put-light',
  colLocationId: 'Id da localização',
  colOrderHuId: 'Id da HU de pedido',
  noNodes: 'Nenhum nó configurado. Adicione nós STOCK e ORDER.',
  remove: 'Remover',
  removeNodeTitle: 'Remover nó',
  removeNodePrefix: 'Remover',
  removeNodeSuffix: 'nó',
  editNodeTitle: 'Editar nó',
  newNodeTitle: 'Novo nó',
  role: 'Função',
  roleTip:
    'Um nó STOCK apresenta ao operador uma HU de estoque de origem; um nó ORDER é um destino de pedido (localização fixa ou compartimento de put-wall).',
  nodeCodeTip:
    'Identificador curto e único deste nó dentro do posto. Exibido ao operador e usado para direcionar a posição.',
  putLightId: 'Id do put-light',
  putLightIdTip:
    'Identificador do dispositivo físico pick/put-to-light ou de exibição neste destino, usado para orientar o operador. Deixe em branco se não houver nenhum.',
  putLightIdPlaceholder: 'Id da luz/tela física',
  orderHuId: 'Id da HU de pedido',
  orderHuIdTip:
    'UUID da unidade de manuseio de pedido (caixa/contêiner) atualmente vinculada a este destino. Normalmente definido pelo sistema; deixe em branco se não houver nenhum.',
  orderHuIdPlaceholder: 'UUID (HU de pedido vinculada atualmente)',
  location: 'Localização',
  locationTip:
    'A localização de dados mestres correspondente a este nó, quando é uma posição fixa/de transportador, localizada pelo código de localização. Deixe em branco para compartimentos dinâmicos de put-wall.',
  searchLocationCode: 'Buscar um código de localização…',
  position: 'Posição',
  positionTip:
    'Índice de ordem que determina onde este nó aparece na disposição do posto e na lista de nós (números menores primeiro).',
  nodeStatusTip:
    'Se este nó está em uso operacional. Os nós INACTIVE permanecem no posto, mas são ignorados durante o trabalho.',
  inTransitCapacity: 'Capacidade em trânsito',
  capacityHint:
    'Quantas unidades de manuseio (contêineres) podem ter um transporte a caminho deste posto ao mesmo tempo, limitado separadamente conforme a classe de modo. Picking é o caso de alto rendimento; Outros cobre decantagem, contagem, QC e manutenção.',
  maxInTransitPicking: 'HUs máx. em trânsito: Picking',
  maxInTransitPickingTip:
    'Limita quantas HUs podem ter um transporte PICKING ativo chegando a este posto ao mesmo tempo. Mais alto mantém o operador abastecido; alto demais satura o buffer de entrada.',
  maxInTransitOther: 'HUs máx. em trânsito: Outros (sem picking)',
  maxInTransitOtherTip:
    'Limita quantas HUs podem ter um transporte sem picking ativo (decantagem, contagem, QC, manutenção) chegando a este posto ao mesmo tempo.',
  noLocations: 'Não há localizações neste armazém.',
  noMatchingCode: 'Nenhum código correspondente.',
  pickLayout: 'Layout de picking',
  pickLayoutTip:
    'Como a tela de picking do operador apresenta os destinos. 1-para-1 = uma caixa de destino por picking; 1-para-N = uma fileira fixa de compartimentos entre os quais o picking é distribuído; Parede de separação = somente os compartimentos acesos são exibidos.',
  pickLayout_ONE_TO_ONE: '1-para-1 (caixa única)',
  pickLayout_ONE_TO_N: '1-para-N (compartimentos)',
  pickLayout_PUT_WALL: 'Parede de separação (compartimentos acesos)',
  pickSlots: 'Compartimentos de picking',
  pickSlotsTip:
    'Quantos compartimentos a tela 1-para-N exibe (os destinos de pedido dispostos da esquerda para a direita). Mínimo de 2.',
  pickSlotsMin: 'Informe 2 compartimentos ou mais.',
  putWallHint:
    'Parede de separação: configure cada compartimento e seu id de put-light como um nó ORDER abaixo (id de put-light + localização).',
  putWallNeedsTopology:
    'O layout de parede de separação precisa da topologia de destino PUT_WALL. Ajuste a topologia acima para PUT_WALL.',
}
