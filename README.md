# Tcordeiro's Horizonte

Terreno ao longe (LOD) para Minecraft 26.3 (Fabric) que funciona em **OpenGL 4.1 — Macs com Apple Silicon** incluídos, onde o Voxy não corre.

- Desenha o terreno já explorado até 64 chunks (16–256), em colunas simplificadas com a cor real das texturas e a luz do jogo.
- Segue a câmara do jogo (balanço da visão, FOV ao correr, zoom).
- Custo baixo: ~0,3 ms por frame no M1.
- **F7** liga/desliga · `/horizonte` mostra o estado e ajusta `distancia`, `qualidade baixa|media|alta`, `gerar ligar|desligar`.
- Opcional dos dois lados: amigos sem o mod entram no mundo normalmente.
- Desliga-se sozinho se o Voxy funcionar no computador ou se o Distant Horizons estiver instalado; pausa com shaders do Iris.

Configuração: `config/thorizonte.json`. Cache: `.minecraft/thorizonte/`.

Limitações: sem texturas no LOD, sem cavernas/saliências, água opaca; em multijogador só mostra o que já carregou.
