// Brazilian Portuguese (Português do Brasil) - tela de slotting (posições de picking + endereçamento por bloco).
export default {
  pickFacesHeading: 'Posições de picking (localização manual · mín/máx)',
  pickFacesIntro:
    'Associe um item + unidade de medida a um endereço de picking fixo. O reabastecimento o preenche até o máximo; as entradas podem ir diretamente para cá quando o picking direto está ativado.',
  colLocation: 'Localização',
  tipLocation:
    'A localização de picking fixa (frente de prateleira/contêiner) à qual este item está atribuído. Os separadores sempre vêm até aqui para buscar este item.',
  colSku: 'Item',
  tipSku: 'O item de estoque atribuído a este frente de picking.',
  colUom: 'UM',
  tipUom: 'Unidade de medida com a qual se separa a partir deste frente; a quantidade separada é contada nessas unidades.',
  colMin: 'Mín',
  tipMin: 'Gatilho de reabastecimento: quando o estoque no frente cai para este valor ou abaixo dele, é gerada uma tarefa de reposição.',
  colMax: 'Máx',
  tipMax: 'Nível de enchimento alvo. O reabastecimento repõe o frente até esta quantidade.',
  colDirect: 'Direto',
  tipDirect:
    'Quando ativado, o estoque recebido deste item pode ser endereçado diretamente no frente de picking, em vez de no armazenamento de reserva.',
  phLocation: 'localização',
  phSku: 'item',
  phUom: 'um',
  phMin: 'mín',
  phMax: 'máx',
  directToPick: 'picking direto',
  addPickFace: 'Adicionar frente de picking',
  blockSlottingHeading: 'Endereçamento por bloco (ASRS / AutoStore / AMR-GTP automatizados)',
  blockSlottingIntro:
    'Associe um item a um bloco de armazenamento (todo o pool, todos os corredores). O motor de endereçamento escolhe a posição real por unidade de manuseio, equilibrando a proximidade da saída, a consolidação do mesmo item, a redundância de corredores e o equilíbrio de ocupação.',
  tipBlockSku:
    'O item de estoque atribuído a um bloco de armazenamento automatizado (pool ASRS / AutoStore / AMR-GTP).',
  colBlock: 'Bloco',
  tipBlock:
    'O bloco de armazenamento (todo o pool, todos os corredores) onde este item pode ser armazenado. O motor de endereçamento escolhe a posição exata por unidade de manuseio.',
  colVelocity: 'Velocidade',
  tipVelocity:
    'Classe de giro que determina o quão perto da saída/picking o item é armazenado. A = giro rápido, C = giro lento.',
  colConsolidate: 'Consolidar',
  tipConsolidate:
    'Quando ativado, o motor prefere agrupar o mesmo item (menos posições e mais densas) em vez de dispersá-lo.',
  colMinAisles: 'Corredores mín.',
  tipMinAisles:
    'Número mínimo de corredores distintos pelos quais este item deve ser distribuído, para redundância caso um corredor fique fora de serviço.',
  colMaxAislePct: '% corredor máx.',
  tipMaxAislePct:
    'Limite da fração de um corredor que um único item pode ocupar, para manter os corredores equilibrados (0 a 1).',
  phBlock: 'bloco…',
  velocityClass: 'Classe de velocidade',
  consolidate: 'consolidar',
  phMinAisles: 'corredores mín.',
  phMaxAislePct: '% corredor máx.',
  addBlockSlotting: 'Adicionar endereçamento por bloco',
}
