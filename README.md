# Keyword-Counter-Activemq

Workflow de computação paralela que conta palavras-chave em um arquivo grande,
com toda a comunicação feita pelo **Apache ActiveMQ** através da API **JMS**.

## Arquitetura

```
Cliente
 ├─► Leitor de linhas pares   ─┐  Produtor
 └─► Leitor de linhas ímpares ─┤
                               ▼
                          FILA_LINHAS
              ┌────────────────┼────────────────┐  Consumidor
              ▼                ▼                ▼
          Contador         Contador         Contador
              └────────────────┼────────────────┘  Publisher
                               ▼
                        TOPICO_PALAVRAS
                               │  Subscriber
                               ▼
                           Dashboard
```

| Componente | Classe | Papel no MOM |
|---|---|---|
| Cliente | `mom.Cliente` | Interface que recebe o arquivo e as palavras-chave e inicia os dois leitores |
| Leitor de Linhas | `mom.LeitorLinhas` | **Produtor**: envia as linhas pares ou ímpares para a fila |
| Fila de Linhas | `FILA_LINHAS` | Fila (ponto a ponto) no Broker |
| Contador de Palavras | `mom.ContadorPalavras` | **Consumidor** da fila e **Publisher** do tópico (workers) |
| Tópico de Palavras | `TOPICO_PALAVRAS` | Tópico (publish/subscribe) no Broker |
| Dashboard | `mom.Dashboard` | **Subscriber**: interface que exibe a contagem de cada palavra |

`mom.Config` guarda apenas o endereço do Broker e os nomes da fila e do tópico.
Nenhum componente conhece o endereço dos outros, só o do servidor do middleware.

## Requisitos

- Java 17 ou mais recente
- Maven 3.8 ou mais recente
- [Apache ActiveMQ Classic](https://activemq.apache.org/components/classic/download/) 5.18 ou mais recente

## Como executar

1. **Inicie o Broker** (na pasta onde o ActiveMQ foi extraído):

   ```
   bin\activemq.bat start
   ```

   O console de administração fica em <http://localhost:8161/admin> (usuário `admin`, senha `admin`)
   e mostra a fila e o tópico, quantas mensagens estão pendentes e quantos consumidores estão ligados.

2. **Gere o executável**:

   ```
   mvn package
   ```

3. **Abra o Dashboard**:

   ```
   java -cp target/keyword-counter.jar mom.Dashboard
   ```

4. **Inicie os Contadores** (o número é a quantidade de workers; padrão 3). Pode ser executado em
   mais de um terminal ou máquina:

   ```
   java -cp target/keyword-counter.jar mom.ContadorPalavras 3
   ```

5. **Abra o Cliente**, escolha o arquivo (texto em UTF-8), informe as palavras-chave separadas por
   vírgula e clique em *Iniciar contagem*:

   ```
   java -jar target/keyword-counter.jar
   ```

A ordem dos passos 3 a 5 não importa: como as mensagens ficam guardadas no Broker, os componentes
podem ser abertos depois que as linhas forem enviadas.

Para usar um Broker em outra máquina, acrescente
`-Dbroker.url=tcp://<host>:61616?jms.prefetchPolicy.queuePrefetch=1` logo depois de `java` em cada
comando.

> Só pode haver um Dashboard aberto por vez, pois a assinatura durável usa um identificador fixo
> (`dashboard`) no Broker.
