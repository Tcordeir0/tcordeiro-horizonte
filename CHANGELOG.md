# Changelog

## 0.1.1

### Corrigido
- **O mundo ao longe "balançava" ao andar.** O terreno ao longe usava a projeção da câmara sem o balanço da visão
  (`bobView`), sem a inclinação ao levar dano e sem a distorção da náusea/portal; os chunks normais balançavam e o
  LOD ficava parado, por isso a costura escorregava a cada passo. Agora o LOD usa exatamente a projeção que o jogo
  usa nesse frame para o mundo (apanhada em `GameRenderer.renderLevel`), incluindo o campo de visão ao correr e o
  zoom (Zoomify); só muda a profundidade.

### Melhorado (costura entre os chunks normais e o terreno ao longe)
- Cor do terreno ao longe = cor **média da textura real** do bloco (com os packs de recursos ligados), tingida pelo
  bioma como no jogo (erva, folhas, água), em vez da cor do mapa. Recalcula-se ao recarregar recursos (F3+T).
- Luz igual à do jogo: sombreamento por face como o vanilla (cima 1,0; norte/sul 0,8; este/oeste 0,6), escurece ao
  entardecer e à noite com a mesma luz do céu que o terreno normal, e oclusão de ambiente simples na base das paredes.
- Com `"quality": "alta"`: junto à costura usa-se sempre a resolução mais fina guardada (células de 2 blocos) numa faixa
  de ~3 chunks e depois engrossa aos poucos (2 → 4 → 8 → 16 blocos, sempre ×2). Fica só na qualidade alta porque
  duplica os quadrados desenhados (−6 a −10 % de FPS no M1); a média continua como na 0.1.0.
- Ligeira variação de cor por célula, para não parecer plástico liso (`colorNoise` na config, ligada por padrão).
- Água com a cor da textura da água × cor da água do bioma, mais escura onde é funda.

### Notas
- As zonas já guardadas no disco mantêm as cores antigas até os chunks voltarem a ser carregados.
- `generate` continua desligado por padrão.
