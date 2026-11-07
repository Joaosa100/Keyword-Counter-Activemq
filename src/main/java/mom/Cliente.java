package mom;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.*;

/*
 * Cliente - início da aplicação.
 *
 * Solicita o arquivo de entrada e as palavras-chave e instancia duas execuções
 * do Leitor de Linhas: uma para as linhas pares e outra para as ímpares.
 * Cada leitor roda em um processo separado e só conhece o endereço do Broker.
 */
public class Cliente extends JFrame {

	private final JTextField campoArquivo = new JTextField(30);
	private final JTextField campoPalavras = new JTextField(30);
	private final JButton botaoIniciar = new JButton("Iniciar contagem");
	private final JTextArea log = new JTextArea(12, 50);

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> new Cliente().setVisible(true));
	}

	public Cliente() {
		super("Cliente - Contagem de Palavras-chave");
		setDefaultCloseOperation(EXIT_ON_CLOSE);

		JButton botaoEscolher = new JButton("Escolher...");
		botaoEscolher.addActionListener(e -> escolherArquivo());
		botaoIniciar.addActionListener(e -> iniciar());

		JPanel formulario = new JPanel(new GridBagLayout());
		formulario.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0; c.gridy = 0;
		formulario.add(new JLabel("Arquivo de entrada:"), c);
		c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
		formulario.add(campoArquivo, c);
		c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
		formulario.add(botaoEscolher, c);

		c.gridx = 0; c.gridy = 1;
		formulario.add(new JLabel("Palavras-chave:"), c);
		c.gridx = 1; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
		formulario.add(campoPalavras, c);

		c.gridx = 1; c.gridy = 2; c.gridwidth = 2; c.fill = GridBagConstraints.NONE;
		formulario.add(new JLabel("Separe as palavras por vírgula. Ex.: java, broker, fila"), c);

		JPanel botoes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		botoes.add(botaoIniciar);

		log.setEditable(false);
		JScrollPane rolagem = new JScrollPane(log);
		rolagem.setBorder(BorderFactory.createTitledBorder("Leitores de linhas"));

		JPanel topo = new JPanel(new BorderLayout());
		topo.add(formulario, BorderLayout.CENTER);
		topo.add(botoes, BorderLayout.SOUTH);

		add(topo, BorderLayout.NORTH);
		add(rolagem, BorderLayout.CENTER);
		pack();
		setLocationRelativeTo(null);
	}

	private void escolherArquivo() {
		JFileChooser seletor = new JFileChooser(new File("."));
		if (seletor.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			campoArquivo.setText(seletor.getSelectedFile().getAbsolutePath());
		}
	}

	private void iniciar() {
		File arquivo = new File(campoArquivo.getText().trim());
		if (!arquivo.isFile()) {
			JOptionPane.showMessageDialog(this, "Escolha um arquivo de entrada válido.");
			return;
		}

		/*
		 * Normalizando as palavras-chave: minúsculas, sem repetição.
		 * Cada palavra-chave deve ser uma única palavra (letras e números).
		 */
		List<String> palavras = Arrays.stream(campoPalavras.getText().split(","))
				.map(p -> p.trim().toLowerCase(Locale.ROOT))
				.filter(p -> !p.isEmpty())
				.distinct()
				.toList();
		if (palavras.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Informe ao menos uma palavra-chave.");
			return;
		}
		for (String palavra : palavras) {
			if (!palavra.matches("[\\p{L}\\p{N}]+")) {
				JOptionPane.showMessageDialog(this, "Palavra-chave inválida: \"" + palavra
						+ "\". Use apenas letras e números, sem espaços.");
				return;
			}
		}

		/*
		 * Identificador desta execução, para o Dashboard separar uma contagem da outra.
		 * É a data e hora de início, então execuções mais novas têm identificadores maiores.
		 */
		String execucao = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
		String listaPalavras = String.join(",", palavras);

		registrar("Execução " + execucao + " - palavras-chave: " + listaPalavras);

		/*
		 * Instanciando as duas execuções do Leitor de Linhas
		 */
		iniciarLeitor(arquivo, "pares", execucao, listaPalavras);
		iniciarLeitor(arquivo, "impares", execucao, listaPalavras);
	}

	/*
	 * Inicia um Leitor de Linhas em um novo processo Java (mesmo classpath do Cliente)
	 * e mostra no log tudo o que ele escrever.
	 */
	private void iniciarLeitor(File arquivo, String paridade, String execucao, String palavras) {
		String java = ProcessHandle.current().info().command()
				.orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

		ProcessBuilder builder = new ProcessBuilder(java,
				"-Dbroker.url=" + Config.URL,
				"-cp", System.getProperty("java.class.path"),
				LeitorLinhas.class.getName(),
				arquivo.getAbsolutePath(), paridade, execucao, palavras);
		builder.redirectErrorStream(true);

		try {
			Process processo = builder.start();
			registrar("Leitor de linhas " + paridade + " iniciado (PID " + processo.pid() + ")");

			Thread saida = new Thread(() -> {
				try (BufferedReader leitor = new BufferedReader(
						new InputStreamReader(processo.getInputStream(), Charset.defaultCharset()))) {
					String linha;
					while ((linha = leitor.readLine()) != null) {
						registrar("[" + paridade + "] " + linha);
					}
					registrar("Leitor de linhas " + paridade + " finalizado (código " + processo.waitFor() + ")");
				} catch (IOException | InterruptedException e) {
					registrar("Erro ao acompanhar o leitor " + paridade + ": " + e.getMessage());
				}
			});
			saida.setDaemon(true);
			saida.start();
		} catch (IOException e) {
			registrar("Não foi possível iniciar o leitor " + paridade + ": " + e.getMessage());
		}
	}

	private void registrar(String texto) {
		SwingUtilities.invokeLater(() -> {
			log.append(texto + "\n");
			log.setCaretPosition(log.getDocument().getLength());
		});
	}
}
