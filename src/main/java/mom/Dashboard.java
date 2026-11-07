package mom;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.jms.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import org.apache.activemq.ActiveMQConnectionFactory;

/*
 * Dashboard - Subscriber do Tópico de Palavras.
 *
 * Recebe as contagens publicadas pelos Contadores e atualiza na interface
 * o total de cada palavra-chave e quantas linhas cada Contador processou.
 */
public class Dashboard extends JFrame implements MessageListener {

	/*
	 * Identificação da assinatura durável no Broker.
	 * Com ela, o Broker guarda as mensagens publicadas enquanto o Dashboard
	 * estiver fechado e as entrega quando ele se conectar de novo.
	 */
	private static final String CLIENT_ID = "dashboard";
	private static final String ASSINATURA = "dashboard-palavras";

	/*
	 * Arquivo onde os totais da execução atual são salvos, para que fechar e
	 * reabrir o Dashboard não perca o que já tinha sido contado.
	 */
	private static final Path ARQUIVO_ESTADO = Path.of("dashboard-estado.properties");

	/* A cada quantas mensagens, ou a cada quantos milissegundos, o estado é salvo */
	private static final int SALVAR_A_CADA_MENSAGENS = 100;
	private static final long SALVAR_A_CADA_MS = 300;

	private final DefaultTableModel tabelaPalavras = new DefaultTableModel(new Object[] { "Palavra-chave", "Ocorrências" }, 0);
	private final DefaultTableModel tabelaContadores = new DefaultTableModel(new Object[] { "Contador", "Linhas processadas" }, 0);
	private final JLabel rotuloExecucao = new JLabel("Execução: aguardando mensagens...");
	private final JLabel rotuloLinhas = new JLabel("Linhas processadas: 0");

	/* Estado acumulado da execução atual; protegido pelo lock do próprio objeto */
	private final Map<String, Integer> totais = new LinkedHashMap<>();
	private final Map<String, Integer> linhasPorContador = new TreeMap<>();
	private String execucaoAtual;
	private int linhasProcessadas;
	private boolean alterado;

	private Connection connection;
	private volatile Message ultimaMensagem;
	private int mensagensNaoSalvas;
	private long ultimoSalvamento = System.currentTimeMillis();

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			Dashboard dashboard = new Dashboard();
			dashboard.setVisible(true);
			dashboard.carregarEstado();
			dashboard.conectar();
		});
	}

	public Dashboard() {
		super("Dashboard - Contagem de Palavras-chave");
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				encerrar();
			}
		});
		setSize(560, 480);
		setLocationRelativeTo(null);

		JPanel topo = new JPanel(new GridLayout(2, 1));
		topo.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		rotuloLinhas.setFont(rotuloLinhas.getFont().deriveFont(Font.BOLD, 14f));
		topo.add(rotuloExecucao);
		topo.add(rotuloLinhas);

		JTable palavras = new JTable(tabelaPalavras);
		palavras.setFont(palavras.getFont().deriveFont(16f));
		palavras.setRowHeight(24);
		palavras.setEnabled(false);

		JTable contadores = new JTable(tabelaContadores);
		contadores.setEnabled(false);

		JSplitPane centro = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
				comTitulo(new JScrollPane(palavras), "Palavras-chave"),
				comTitulo(new JScrollPane(contadores), "Contadores (workers)"));
		centro.setResizeWeight(0.65);

		add(topo, BorderLayout.NORTH);
		add(centro, BorderLayout.CENTER);

		/* Redesenha a interface periodicamente, só quando o estado mudou */
		new Timer(200, e -> redesenhar()).start();
	}

	private static JComponent comTitulo(JComponent componente, String titulo) {
		componente.setBorder(BorderFactory.createTitledBorder(titulo));
		return componente;
	}

	private void conectar() {
		try {

			/*
			 * Estabelecendo conexão com o Servidor JMS.
			 * O clientID identifica este assinante para a assinatura durável.
			 */
			ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(Config.URL);
			connection = connectionFactory.createConnection();
			connection.setClientID(CLIENT_ID);

			/*
			 * Criando Session com confirmação manual (CLIENT_ACKNOWLEDGE).
			 * As mensagens só são confirmadas ao Broker depois que os totais são
			 * salvos em disco; as não confirmadas são entregues de novo na próxima
			 * vez que o Dashboard abrir.
			 */
			Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

			/*
			 * Criando Topic
			 */
			Topic topico = session.createTopic(Config.TOPICO_PALAVRAS);

			/*
			 * Criando Subscriber durável e setando Listener
			 */
			MessageConsumer subscriber = session.createDurableSubscriber(topico, ASSINATURA);
			subscriber.setMessageListener(this);

			connection.start();

		} catch (InvalidClientIDException e) {

			/* O Broker recusa um segundo assinante com o mesmo clientID */
			fecharConexao(connection);
			JOptionPane.showMessageDialog(this, "Já existe um Dashboard aberto.",
					"Dashboard", JOptionPane.WARNING_MESSAGE);
			System.exit(0);

		} catch (JMSException e) {
			e.printStackTrace();
			fecharConexao(connection);
			connection = null;
			JOptionPane.showMessageDialog(this,
					"Não foi possível conectar ao Broker em " + Config.URL + "\n" + e.getMessage(),
					"Erro de conexão", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static void fecharConexao(Connection connection) {
		if (connection != null) {
			try {
				connection.close();
			} catch (JMSException ignorada) {
			}
		}
	}

	/*
	 * Fechamento pelo botão "X": para a entrega de mensagens, salva o estado,
	 * confirma ao Broker o que já foi contado e só então encerra.
	 */
	private void encerrar() {
		if (connection != null) {
			try {
				connection.stop();
				salvarEstado();
				if (ultimaMensagem != null) {
					ultimaMensagem.acknowledge();
				}
			} catch (JMSException | IOException e) {
				e.printStackTrace();
			}
			fecharConexao(connection);
		}
		System.exit(0);
	}

	/*
	 * Chamado pelo cliente JMS a cada contagem publicada no tópico.
	 * Acumula a contagem e, de tempos em tempos, salva o estado e confirma
	 * ao Broker todas as mensagens recebidas até aqui.
	 */
	@Override
	public void onMessage(Message message) {
		try {
			if (message instanceof MapMessage) {
				MapMessage resultado = (MapMessage) message;
				String execucao = resultado.getStringProperty(Config.PROP_EXECUCAO);
				String contador = resultado.getStringProperty(Config.PROP_CONTADOR);

				Map<String, Integer> contagens = new LinkedHashMap<>();
				Enumeration<?> nomes = resultado.getMapNames();
				while (nomes.hasMoreElements()) {
					String palavra = (String) nomes.nextElement();
					contagens.put(palavra, resultado.getInt(palavra));
				}

				acumular(execucao, contador, contagens);
			}

			ultimaMensagem = message;
			mensagensNaoSalvas++;
			long agora = System.currentTimeMillis();
			if (mensagensNaoSalvas >= SALVAR_A_CADA_MENSAGENS || agora - ultimoSalvamento >= SALVAR_A_CADA_MS) {
				salvarEstado();
				message.acknowledge();
				mensagensNaoSalvas = 0;
				ultimoSalvamento = agora;
			}
		} catch (JMSException | IOException e) {
			e.printStackTrace();
		}
	}

	private synchronized void acumular(String execucao, String contador, Map<String, Integer> contagens) {

		/*
		 * O identificador da execução é a data e hora em que o Cliente a iniciou.
		 * Mensagens de uma execução mais antiga que a atual (linhas que sobraram na
		 * fila de uma contagem interrompida) são ignoradas; uma execução mais nova
		 * zera o painel.
		 */
		if (execucaoAtual != null) {
			int comparacao = execucao.compareTo(execucaoAtual);
			if (comparacao < 0) {
				return;
			}
			if (comparacao > 0) {
				totais.clear();
				linhasPorContador.clear();
				linhasProcessadas = 0;
			}
		}
		execucaoAtual = execucao;

		contagens.forEach((palavra, quantidade) -> totais.merge(palavra, quantidade, Integer::sum));
		linhasPorContador.merge(contador, 1, Integer::sum);
		linhasProcessadas++;
		alterado = true;
	}

	/*
	 * Grava o estado em um arquivo temporário e depois o renomeia, para que o
	 * arquivo nunca fique pela metade se o Dashboard for fechado durante a gravação.
	 */
	private synchronized void salvarEstado() throws IOException {
		if (execucaoAtual == null) {
			return;
		}
		Properties estado = new Properties();
		estado.setProperty("execucao", execucaoAtual);
		estado.setProperty("linhas", String.valueOf(linhasProcessadas));
		totais.forEach((palavra, total) -> estado.setProperty("palavra." + palavra, String.valueOf(total)));
		linhasPorContador.forEach((nome, linhas) -> estado.setProperty("contador." + nome, String.valueOf(linhas)));

		Path temporario = Path.of(ARQUIVO_ESTADO + ".tmp");
		try (Writer escritor = Files.newBufferedWriter(temporario, StandardCharsets.UTF_8)) {
			estado.store(escritor, null);
		}
		Files.move(temporario, ARQUIVO_ESTADO, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/*
	 * Restaura os totais salvos da última execução, se existirem
	 */
	private synchronized void carregarEstado() {
		if (!Files.exists(ARQUIVO_ESTADO)) {
			return;
		}
		Properties estado = new Properties();
		try (Reader leitor = Files.newBufferedReader(ARQUIVO_ESTADO, StandardCharsets.UTF_8)) {
			estado.load(leitor);

			Map<String, Integer> palavras = new LinkedHashMap<>();
			Map<String, Integer> contadores = new TreeMap<>();
			for (String chave : estado.stringPropertyNames()) {
				if (chave.startsWith("palavra.")) {
					palavras.put(chave.substring("palavra.".length()), Integer.parseInt(estado.getProperty(chave)));
				} else if (chave.startsWith("contador.")) {
					contadores.put(chave.substring("contador.".length()), Integer.parseInt(estado.getProperty(chave)));
				}
			}

			execucaoAtual = estado.getProperty("execucao");
			linhasProcessadas = Integer.parseInt(estado.getProperty("linhas", "0"));
			totais.putAll(palavras);
			linhasPorContador.putAll(contadores);
		} catch (IOException | RuntimeException e) {

			/* Arquivo ilegível: o Dashboard começa vazio, mas continua funcionando */
			e.printStackTrace();
			return;
		}
		alterado = true;
		redesenhar();
	}

	/*
	 * Copia o estado e atualiza as tabelas na thread do Swing
	 */
	private void redesenhar() {
		String execucao;
		int linhas;
		Map<String, Integer> copiaTotais;
		Map<String, Integer> copiaContadores;
		synchronized (this) {
			if (!alterado) {
				return;
			}
			alterado = false;
			execucao = execucaoAtual;
			linhas = linhasProcessadas;
			copiaTotais = new LinkedHashMap<>(totais);
			copiaContadores = new TreeMap<>(linhasPorContador);
		}

		rotuloExecucao.setText("Execução: " + execucao);
		rotuloLinhas.setText("Linhas processadas: " + linhas);

		tabelaPalavras.setRowCount(0);
		copiaTotais.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> tabelaPalavras.addRow(new Object[] { e.getKey(), e.getValue() }));

		tabelaContadores.setRowCount(0);
		copiaContadores.forEach((nome, total) -> tabelaContadores.addRow(new Object[] { nome, total }));
	}
}
