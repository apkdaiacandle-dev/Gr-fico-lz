# LZGraph 1.4 — Reconhecimento avançado + taxa configurável

## O que foi evoluído
- Captura de tela via MediaProjection com atualização configurável.
- Taxas disponíveis: 100 ms, 250 ms, 500 ms, 750 ms, 1 s, 2 s, 3 s e 5 s.
- Preferência salva no Android e aplicada pelo serviço sem precisar reinstalar o app.
- Detecção visual de regiões verdes/vermelhas e agrupamento de candidatos a candles.
- Heurísticas para Doji, Martelo, Estrela Cadente, Engolfo de Alta/Baixa, sequências de 3 candles e pressão compradora/vendedora.
- Tendência em ALTA, ALTA LEVE, BAIXA, BAIXA LEVE ou LATERAL.
- Painel mostra padrão, tendência, confiança heurística, detalhes, quantidade de candles e intervalo atual.
- Painel permanece sobre outros aplicativos, é arrastável e pode ser fechado.

## Como mudar a velocidade
Na tela inicial, toque na engrenagem ou no cartão **Taxa de atualização**. Quanto menor o intervalo, mais frequente a análise e maior o uso de CPU/bateria. 500 ms é o equilíbrio recomendado.

## Limitação importante
O reconhecimento é visual/heurístico. Ele não é um modelo de previsão de mercado e não garante acerto. O desempenho depende do tema, cores, zoom e qualidade do gráfico exibido.
