package datawave.query.rewrite.jexl.functions;

import java.util.ArrayList;
import java.util.Set;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.hadoop.io.Text;

import datawave.query.data.parsers.DatawaveKey;
import datawave.query.rewrite.predicate.EventDataQueryFilter;
import datawave.query.rewrite.tld.TLD;
import datawave.query.util.Tuple2;

public class TermFrequencyAggregator extends IdentityAggregator {
    
    public TermFrequencyAggregator(Set<String> fieldsToKeep, EventDataQueryFilter attrFilter) {
        super(fieldsToKeep, attrFilter);
    }
    
    @Override
    protected Tuple2<String,String> parserFieldNameValue(Key topKey) {
        DatawaveKey parser = new DatawaveKey(topKey);
        return new Tuple2<String,String>(parser.getFieldName(), parser.getFieldValue());
    }
    
    @Override
    protected ByteSequence parseFieldNameValue(ByteSequence cf, ByteSequence cq) {
        return TLD.parseFieldAndValueFromTF(cq);
    }
    
    @Override
    protected ByteSequence parsePointer(ByteSequence qualifier) {
        ArrayList<Integer> deezNulls = TLD.instancesOf(0, qualifier, -1);
        final int stop = deezNulls.get(1);
        return qualifier.subSequence(0, stop);
    }
    
    @Override
    protected boolean samePointer(Text row, ByteSequence pointer, Key key) {
        if (row.equals(key.getRow())) {
            ByteSequence pointer2 = parsePointer(key.getColumnQualifierData());
            return (pointer.equals(pointer2));
        }
        return false;
    }
    
}
