package mom;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;

/*
 * Leitor de Linhas - Produtor da Fila de Linhas.
 *
 * O Cliente inicia duas execuções deste programa: uma para as linhas pares
 * e outra para as linhas ímpares. Cada execução lê o arquivo e insere na fila
 * do Broker somente as linhas da sua paridade (a primeira linha é a de número 1).
 *
 * Uso: LeitorLinhas <arquivo> <pares|impares> <execucao> <palavra1,palavra2,...>
 */
public class LeitorLinhas {

	public static void main(String[] args) throws Exception {
		if (args.length != 4 || !(args[1].equals("pares") || args[1].equals("impares"))) {
			System.err.println("Uso: LeitorLinhas <arquivo> <pares|impares> <execucao> <palavras>");
			System.exit(1);
		}

		Path arquivo = Path.of(args[0]);
		boolean pares = args[1].equals("pares");
		String execucao = args[2];
		String palavras = args[3];

		/*
		 * Estabelecendo conexão com o Servidor JMS
		 */
		ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(Config.URL);
		Connection connection = connectionFactory.createConnection();
		connection.start();

		/*
		 * Criando Session
		 */
		Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

		/*
		 * Criando Queue
		 */
		Destination destination = session.createQueue(Config.FILA_LINHAS);

		/*
		 * Criando Produtor.
		 * PERSISTENT: o Broker grava a mensagem em disco, então as linhas
		 * esperam na fila mesmo que nenhum Contador esteja online no momento.
		 */
		MessageProducer producer = session.createProducer(destination);
		producer.setDeliveryMode(DeliveryMode.PERSISTENT);

		/*
		 * Lendo o arquivo e enviando cada linha da paridade escolhida para a fila
		 */
		int numeroLinha = 0;
		int enviadas = 0;
		try (BufferedReader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
			String linha;
			while ((linha = leitor.readLine()) != null) {
				numeroLinha++;
				if ((numeroLinha % 2 == 0) != pares) {
					continue;
				}

				TextMessage message = session.createTextMessage(linha);
				message.setStringProperty(Config.PROP_EXECUCAO, execucao);
				message.setStringProperty(Config.PROP_PALAVRAS, palavras);
				message.setIntProperty(Config.PROP_LINHA, numeroLinha);

				/*
				 * Enviando Mensagem
				 */
				producer.send(message);
				enviadas++;
			}
		}

		System.out.println("Leitor de linhas " + args[1] + ": " + enviadas + " linhas enviadas para a fila");

		producer.close();
		session.close();
		connection.close();
	}
}
