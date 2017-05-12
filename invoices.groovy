#!/usr/bin/env groovy

@Grapes([
        @Grab(group = 'org.asciidoctor', module = 'asciidoctorj', version = '1.5.4'),
        @Grab(group = 'org.asciidoctor', module = 'asciidoctorj-pdf', version = '1.5.0-alpha.11'),
        @Grab(group = 'org.jruby', module = 'jruby-complete', version = '1.7.26'),
        @Grab(group = 'org.yaml', module = 'snakeyaml', version = '1.16'),
        @Grab(group = 'org.ccil.cowan.tagsoup', module = 'tagsoup', version = '1.2')
])
import org.asciidoctor.Asciidoctor
import org.yaml.snakeyaml.Yaml

import java.nio.file.Paths
import java.text.*

import static org.asciidoctor.Asciidoctor.Factory.create
import static org.asciidoctor.AttributesBuilder.attributes
import static org.asciidoctor.OptionsBuilder.options

def cli = new CliBuilder(usage: 'invoices.groovy -t [template] [yml filename]')
cli.t(args: 1, argName: 'template', "template name (default to: 'template/template.adoc')")
def clioptions = cli.parse(args)
def arguments = clioptions.arguments()

def template = "template/template.adoc"
if (clioptions.t) {
  template = clioptions.t
}

if (arguments.size == 0) {
    println "Missing input filename argument."
    cli.usage()
    System.exit(0)
}

def inputFile = new File(arguments[0])
if (!inputFile.exists()) {
    println "File ${inputFile} not found."
    System.exit(0)
}

Invoice invoiceData = Invoice.fromFile(inputFile)

def attributes = attributes()
        .attribute('pdf-stylesdir', 'template/themes')
        .attribute('pdf-style', 'basic.yml')
        .attributes(invoiceData.asMap)
        .asMap()

Asciidoctor asciidoctor = create()
asciidoctor.convertFile(
        new File(template),
        options()
                .attributes(attributes)
                .toFile(Paths.get('build', inputFile.name.replaceFirst(~/\.[^\.]+$/, '') + '.pdf').toFile())
                .backend('pdf')
                .get())

class Invoice {

    private LinkedHashMap map

    static Invoice fromFile(File f) {
        Yaml yaml = new Yaml()
        Map m = yaml.loadAs(new FileInputStream(f), LinkedHashMap.class)
        return new Invoice(m)
    }

    private Invoice(input) {
        this.map = input

        //calculations
        sumUp()
        groupByTaxes()

        //formatting
        formatDates()
        formatNumbers(map)
        map['positions'].each { m -> formatNumbers(m) }

        //flattening
        flattenPositions()

        // print map
    }

    def getAsMap() {
        map.getClass().newInstance(map)
    }

    private convertAmountToText(amount) {
        def parser = new XmlSlurper(new org.ccil.cowan.tagsoup.Parser())
        def page = parser.parse("https://slownie.pl/${amount}")

        return page.depthFirst().find { it.getProperty('@id') == 'dataWord' }
    }

    private convertAmountToEnglishText(amount) {
        // curl -v -XPOST --data "action=ajax_number_spell_words&number=920&type=0&locale=en_GB" https://www.tools4noobs.com/online_tools/number_spell_words/
        new URL("http://www.dataaccess.com/webservicesserver/numberconversion.wso/NumberToWords/JSON/debug?ubiNum=${amount}").getText().replace("\"", "")
    }

    private  formatDates() {
        map.findAll { it -> it.value.class == Date }
                .each { e -> map.put(e.key, e.value.format("yyyy/MM/dd")) }
    }

    private sumUp() {
        map.positions.each { p ->
            p['position-total'] = p['quantity'] * p['unit-price']
            if (!p['tax'].contains('%')) {
              p['position-tax'] = p['position-total'] * new DecimalFormat("0.##%").parse("0%")
            } else {
              p['position-tax'] = p['position-total'] * new DecimalFormat("0.##%").parse(p['tax'])
            }
            p['position-total-gross'] = p['position-total'] + p['position-tax']
        }

        map['total-amount-net'] = map['positions'].inject(0, {
            acc, p -> acc + p['position-total']
        })
        map['total-amount-tax'] = map['positions'].inject(0, {
            acc, p -> acc + p['position-tax']
        })
        map['total-amount-gross'] = map['positions'].inject(0, {
            acc, p -> acc + p['position-total-gross']
        })
        map['total-amount-words'] = convertAmountToText(map['total-amount-gross'])
        map['total-amount-words-en'] = convertAmountToEnglishText(map['total-amount-gross'])
    }

    private formatNumbers(Map m) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.GERMAN)
        nf.setMaximumFractionDigits(2)
        nf.setMinimumFractionDigits(2)
        nf.setGroupingUsed(false)

        m.findAll { it -> it.key != 'quantity' && it.value.class in [Double, Integer, Long] }
            .each { e -> m.put(e.key, nf.format(e.value)) }
    }

    private flattenPositions() {
        map.positions.eachWithIndex { position, i ->
            position.each { e -> map["position-${i}-${e.key}"] = e.value}
        }
    }

    private groupByTaxes() {
        map.positions.groupBy { it.tax }
            .each { e -> map["vat-${e.key.replaceAll(/[%\/\.]/, "")}-net"] = e.value.inject(0, { acc, t -> acc + t['position-total']})}
        map.positions.groupBy { it.tax }
                .each { e -> map["vat-${e.key.replaceAll(/[%\/\.]/, "")}-tax"] = e.value.inject(0, { acc, t -> acc + t['position-tax']})}
        map.positions.groupBy { it.tax }
                .each { e -> map["vat-${e.key.replaceAll(/[%\/\.]/, "")}-gross"] = e.value.inject(0, { acc, t -> acc + t['position-total-gross']})}
    }

}
